package et.alpha.nchat.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** [MeshEnabledStore]: on by default, and a stop is remembered. */
@RunWith(AndroidJUnit4::class)
class MeshEnabledStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = clear()

    @After
    fun tearDown() = clear()

    private fun clear() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().remove("mesh_enabled").commit()
    }

    @Test
    fun theMeshIsOnUntilTheUserTurnsItOff() {
        assertTrue(MeshEnabledStore(context).enabled.value)
    }

    @Test
    fun turningItOffIsRememberedByTheNextStore() {
        MeshEnabledStore(context).setEnabled(false)

        // A new instance reads the preferences afresh, as the next process does.
        assertFalse(MeshEnabledStore(context).enabled.value)

        MeshEnabledStore(context).setEnabled(true)
        assertTrue(MeshEnabledStore(context).enabled.value)
    }
}
