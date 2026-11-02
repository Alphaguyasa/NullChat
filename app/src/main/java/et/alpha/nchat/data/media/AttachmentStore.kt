package et.alpha.nchat.data.media

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where image bytes live on disk.
 *
 * Files rather than a Room BLOB column. A 14 KB image is small, but every query
 * that touches the `messages` table would carry those bytes through the cursor,
 * and the chat list re-queries on every incoming packet. The database stores a
 * filename; the bytes sit beside it.
 *
 * In `filesDir`, which is app-private and excluded from the media scanner, so
 * received images do not appear in the user's gallery. That matters for a
 * mesh app: pictures arrive from whoever is in radio range, and silently
 * publishing them into the camera roll is not a decision to make on the user's
 * behalf.
 */
@Singleton
class AttachmentStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val directory: File by lazy {
        File(context.filesDir, DIRECTORY).apply { mkdirs() }
    }

    /**
     * Writes [bytes] for [messageId] and returns the stored file name.
     *
     * Named from the message id, which is unique and already the primary key of
     * the row that points here, so a re-delivered message overwrites its own file
     * rather than accumulating copies.
     *
     * @return the file name to store on the message row, or null if the write failed.
     */
    suspend fun write(messageId: String, bytes: ByteArray): String? =
        withContext(Dispatchers.IO) {
            val name = fileNameFor(messageId)
            runCatching {
                // Written to a temp file and renamed, so a process death midway
                // leaves no half-written JPEG that renders as a grey box forever.
                val temp = File(directory, "$name.tmp")
                temp.writeBytes(bytes)
                val target = File(directory, name)
                if (!temp.renameTo(target)) {
                    temp.delete()
                    error("could not rename attachment into place")
                }
                name
            }.onFailure {
                Log.w(TAG, "failed to store attachment: ${it.message}")
            }.getOrNull()
        }

    /** Reads stored bytes, or null if the file is gone. */
    suspend fun read(fileName: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = File(directory, fileName)
        if (!file.isFile) return@withContext null
        runCatching { file.readBytes() }
            .onFailure { Log.w(TAG, "failed to read attachment: ${it.message}") }
            .getOrNull()
    }

    /** Absolute path for a stored attachment, for Compose to decode from. */
    fun pathFor(fileName: String): String = File(directory, fileName).absolutePath

    suspend fun delete(fileName: String): Boolean = withContext(Dispatchers.IO) {
        File(directory, fileName).delete()
    }

    /**
     * Deletes files no message row points at any more, among those written
     * before [writtenBefore].
     *
     * Orphans come from a process killed between a delete's row and file
     * steps, or between an image's file write and its row insert. Nothing in
     * SQLite can reach outside the database to remove them, so without this
     * they stay on disk for good.
     *
     * [writtenBefore] keeps the prune off files this process is still using: an
     * incoming image is written a moment before its row is inserted, and a
     * prune that read the live names in that gap would delete a picture whose
     * row then points at nothing.
     *
     * @return how many files were deleted.
     */
    suspend fun pruneExcept(liveFileNames: Set<String>, writtenBefore: Long): Int =
        withContext(Dispatchers.IO) {
            val files = directory.listFiles() ?: return@withContext 0
            files.count { file ->
                isOrphanAttachment(file.name, file.lastModified(), liveFileNames, writtenBefore) &&
                    file.delete()
            }
        }

    private fun fileNameFor(messageId: String): String = "$messageId.jpg"

    private companion object {
        const val TAG = "AttachmentStore"
        const val DIRECTORY = "attachments"
    }
}

/**
 * Whether the prune may delete a file: written before [writtenBefore], and
 * either a leftover temp file or one no row points at.
 *
 * `File.lastModified` is 0 when it cannot be read; such a file counts as old,
 * which is right for a leftover and harmless for a live one, kept by name.
 */
internal fun isOrphanAttachment(
    name: String,
    lastModified: Long,
    liveFileNames: Set<String>,
    writtenBefore: Long,
): Boolean = lastModified < writtenBefore && (name.endsWith(".tmp") || name !in liveFileNames)
