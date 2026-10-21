package et.alpha.nchat

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.AndroidEntryPoint
import et.alpha.nchat.data.settings.AppLockStore
import et.alpha.nchat.service.MeshForegroundService
import et.alpha.nchat.service.MessageNotificationPolicy
import et.alpha.nchat.ui.MeshChatScreen
import et.alpha.nchat.ui.lock.LockScreen
import et.alpha.nchat.ui.lock.SystemUnlock
import et.alpha.nchat.ui.permissions.PermissionGate
import et.alpha.nchat.ui.permissions.RadioGate
import et.alpha.nchat.ui.theme.NChatTheme
import javax.inject.Inject

/**
 * A [FragmentActivity] rather than a plain ComponentActivity only because the
 * system unlock prompt (androidx.biometric) hosts itself in a fragment.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var appLock: AppLockStore

    /**
     * A thread a notification asked to open, until the screen host takes it.
     *
     * Activity state rather than a navigation call because the host may not
     * be composed yet — the permission and radio gates come first — and the
     * request has to survive until it is.
     */
    private var openPeerRequest by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only on a fresh start: after a rotation the intent is the same one
        // already handled, and replaying it would yank the user back to it.
        if (savedInstanceState == null) handleOpenRequest(intent)
        enableEdgeToEdge()
        setContent {
            val locked by appLock.gate.locked.collectAsState()
            // Asks once each time the lock comes down, so opening the app goes
            // straight to the fingerprint; the button covers a cancelled prompt.
            LaunchedEffect(locked) { if (locked) promptUnlock() }
            NChatTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    // The chats stay composed under the lock, so the mesh keeps
                    // running, but hidden from TalkBack and friends: the lock
                    // screen covers them visually and must for them too.
                    Box(
                        modifier = if (locked) {
                            Modifier.fillMaxSize().clearAndSetSemantics {}
                        } else {
                            Modifier.fillMaxSize()
                        },
                    ) {
                        AppContent(
                            openPeerRequest = openPeerRequest,
                            onOpenRequestHandled = { openPeerRequest = null },
                        )
                    }
                    if (locked) LockScreen(onUnlock = ::promptUnlock)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val enabled = appLock.enabled.value
        appLock.gate.onForeground(SystemClock.elapsedRealtime(), enabled)
        // Keeps the chats out of the recent-apps thumbnail, which would
        // otherwise show the last screen to anyone holding the phone.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(!enabled)
        }
    }

    override fun onStop() {
        super.onStop()
        // A rotation stops the activity too, and is not leaving the app.
        if (!isChangingConfigurations) {
            appLock.gate.onBackground(SystemClock.elapsedRealtime(), appLock.enabled.value)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenRequest(intent)
    }

    private fun promptUnlock() {
        SystemUnlock.prompt(this) { appLock.gate.unlock() }
    }

    private fun handleOpenRequest(intent: Intent?) {
        MessageNotificationPolicy.peerIdFromExtra(intent?.getStringExtra(EXTRA_PEER_ID))
            ?.let { openPeerRequest = it }
    }

    companion object {
        /** Peer whose thread to open, set by a message notification. */
        const val EXTRA_PEER_ID: String = "et.alpha.nchat.extra.PEER_ID"
    }
}

@Composable
private fun AppContent(
    openPeerRequest: String?,
    onOpenRequestHandled: () -> Unit,
) {
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        // The service is started from inside the gate, never outside
        // it: starting the mesh without permissions produces a
        // foreground service that can only fail, and on SDK 34+ that
        // is grounds for the platform to kill it outright.
        // Permissions first, then radios: asking someone to switch
        // Bluetooth on before they have granted permission to use it
        // is asking them to fix the wrong thing.
        PermissionGate(modifier = Modifier.padding(innerPadding)) {
            RadioGate(modifier = Modifier.padding(innerPadding)) {
                MeshHost(
                    openPeerRequest = openPeerRequest,
                    onOpenRequestHandled = onOpenRequestHandled,
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }
    }
}

@Composable
private fun MeshHost(
    openPeerRequest: String?,
    onOpenRequestHandled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { MeshForegroundService.start(context) }
    MeshChatScreen(
        modifier = modifier,
        openPeerRequest = openPeerRequest,
        onOpenRequestHandled = onOpenRequestHandled,
    )
}

@Preview(showBackground = true)
@Composable
private fun MainActivityPreview() {
    NChatTheme {
        MeshChatScreen()
    }
}
