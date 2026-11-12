package et.alpha.nchat.ui.settings

/**
 * The line in Settings that says which build this phone is running.
 *
 * Phone checks compare two or three devices, and before this every build was
 * "1.0 (1)", so nobody could tell whether both phones had the fix under test.
 * CI stamps the run number into versionCode and versionName and the commit into
 * [commit]; builds made on a laptop say "local" instead of a commit.
 */
internal object BuildIdentity {

    /** Shown when the build carries no commit, i.e. it was not built by CI. */
    const val LOCAL = "local"

    private const val SHORT_COMMIT = 7

    /**
     * "1.0.214 (214) · 3f9c2ab". The commit is cut to seven hex characters and
     * anything that is not hex is refused, so a stray value in the build
     * environment cannot put arbitrary text on the screen.
     */
    fun label(versionName: String, versionCode: Int, commit: String): String =
        "$versionName ($versionCode) · ${shortCommit(commit)}"

    fun shortCommit(commit: String): String {
        val trimmed = commit.trim().lowercase()
        if (trimmed.length < SHORT_COMMIT || !trimmed.all { it in '0'..'9' || it in 'a'..'f' }) return LOCAL
        return trimmed.take(SHORT_COMMIT)
    }
}
