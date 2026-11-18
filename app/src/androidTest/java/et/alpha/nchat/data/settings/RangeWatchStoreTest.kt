package et.alpha.nchat.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** [RangeWatchStore]: empty by default, and a watch outlives the process. */
@RunWith(AndroidJUnit4::class)
class RangeWatchStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val maya = "a1b2c3d4e5f60718"
    private val sam = "0011223344556677"

    @Before
    fun setUp() = clear()

    @After
    fun tearDown() = clear()

    private fun clear() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().remove("range_watch").commit()
    }

    @Test
    fun nobodyIsWatchedUntilTheUserAsks() {
        assertEquals(emptySet<String>(), RangeWatchStore(context).watched.value)
    }

    @Test
    fun watchesAreRememberedByTheNextStore() {
        val store = RangeWatchStore(context)
        store.setWatched(maya, true)
        store.setWatched(sam, true)
        store.setWatched(maya, false)

        // A new instance reads the preferences afresh, as the next process does.
        assertEquals(setOf(sam), RangeWatchStore(context).watched.value)
    }
}
