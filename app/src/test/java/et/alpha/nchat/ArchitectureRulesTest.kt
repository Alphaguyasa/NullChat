package et.alpha.nchat

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Executable versions of the hard rules in CLAUDE.md.
 *
 * These exist because both rules are invisible at the point they get broken: a
 * stray import compiles perfectly and only shows up much later, as a transport
 * that cannot be swapped or a protocol layer that will not run on the JVM. A
 * review catches them only if the reviewer remembers to look.
 */
class ArchitectureRulesTest {

    @Test
    fun `Nearby Connections is imported in NearbyTransport only`() {
        val offenders = sourceFiles()
            .filter { file -> file.readLines().any { it.startsWith("import com.google.android.gms.nearby") } }
            .map { it.name }
            .sorted()

        assertEquals(
            listOf("NearbyTransport.kt"),
            offenders,
            "Nearby Connections must stay behind the Transport interface. " +
                "Route new transport work through Transport, not through these files.",
        )
    }

    @Test
    fun `the protocol and router layers stay free of Android`() {
        val pure = sourceFiles().filter { file ->
            val path = file.invariantPath()
            "/mesh/protocol/" in path || "/mesh/router/" in path
        }

        // Guards against the test silently passing because the paths moved.
        assertTrue(pure.isNotEmpty(), "found no protocol or router sources to check")

        val offenders = pure
            .filter { file ->
                file.readLines().any {
                    it.startsWith("import android.") || it.startsWith("import androidx.")
                }
            }
            .map { it.name }
            .sorted()

        assertEquals(
            emptyList<String>(),
            offenders,
            "mesh/protocol and mesh/router must be pure Kotlin so the mesh is " +
                "unit-testable on the JVM without an emulator.",
        )
    }

    // The bug: fonts came from Play Services over the internet, so on a phone
    // that was never online peer ids and fingerprints fell back to a
    // proportional font. Bundled fonts need no network; this keeps it that way.
    @Test
    fun `fonts ship in the APK, not over the network`() {
        val offenders = sourceFiles()
            .filter { file -> file.readLines().any { it.startsWith("import androidx.compose.ui.text.googlefonts") } }
            .map { it.name }
            .sorted()

        assertEquals(
            emptyList<String>(),
            offenders,
            "Downloadable fonts need the internet. Add the font file to res/font instead.",
        )
    }

    private fun sourceFiles(): List<File> =
        sourceRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun File.invariantPath(): String = path.replace('\\', '/')

    /**
     * Gradle does not guarantee which directory unit tests run from, so this
     * walks up looking for the source set rather than assuming a working
     * directory, and fails loudly rather than vacuously passing on an empty scan.
     */
    private fun sourceRoot(): File {
        var directory: File? = File(System.getProperty("user.dir"))
        while (directory != null) {
            for (candidate in listOf("src/main/java", "app/src/main/java")) {
                val resolved = File(directory, candidate)
                if (resolved.isDirectory) return resolved
            }
            directory = directory.parentFile
        }
        error("could not locate the main source set from ${System.getProperty("user.dir")}")
    }
}
