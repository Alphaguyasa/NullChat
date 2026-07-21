package et.alpha.nchat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import dagger.hilt.android.AndroidEntryPoint
import et.alpha.nchat.service.MeshForegroundService
import et.alpha.nchat.ui.MeshChatScreen
import et.alpha.nchat.ui.permissions.PermissionGate
import et.alpha.nchat.ui.permissions.RadioGate
import et.alpha.nchat.ui.theme.NChatTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NChatTheme {
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
                            MeshHost(modifier = Modifier.padding(innerPadding))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MeshHost(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { MeshForegroundService.start(context) }
    MeshChatScreen(modifier = modifier)
}

@Preview(showBackground = true)
@Composable
private fun MainActivityPreview() {
    NChatTheme {
        MeshChatScreen()
    }
}
