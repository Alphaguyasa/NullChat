package et.alpha.nchat.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** [DeletedMessageStore]: the Everyone trim cannot push out the user's deletions. */
@RunWith(AndroidJUnit4::class)
class DeletedMessageStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = clear()

    @After
    fun tearDown() = clear()

    private fun clear() {
        context.getSharedPreferences("deleted_messages", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun aFloodOfTrimmedBroadcastsKeepsTheUsersDeletion() = runBlocking {
        val store = DeletedMessageStore(context)
        store.remember("deleted-by-user")

        // A crowd's broadcasts trimmed from Everyone, past both caps.
        store.rememberTrimmed(List(6000) { "trimmed-$it" })

        assertTrue(store.isDeleted("deleted-by-user"))
        assertTrue(store.isDeleted("trimmed-5999"))
        assertFalse(store.isDeleted("trimmed-0"))
    }

    @Test
    fun bothSetsSurviveARestart() = runBlocking {
        DeletedMessageStore(context).apply {
            remember("deleted-by-user")
            rememberTrimmed(listOf("trimmed"))
        }

        // A new instance reads the preferences afresh, as the next process does.
        val reopened = DeletedMessageStore(context)
        assertTrue(reopened.isDeleted("deleted-by-user"))
        assertTrue(reopened.isDeleted("trimmed"))
        assertFalse(reopened.isDeleted("never-deleted"))
    }
}
