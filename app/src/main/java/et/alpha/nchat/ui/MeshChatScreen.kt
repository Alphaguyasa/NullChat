package et.alpha.nchat.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import et.alpha.nchat.ui.theme.NChatTheme

/**
 * Placeholder screen used to verify the build runs on a device.
 * Replaced by PeerList once a transport exists.
 */
@Composable
fun MeshChatScreen(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Mesh Chat",
            style = MaterialTheme.typography.headlineMedium
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun MeshChatScreenPreview() {
    NChatTheme {
        MeshChatScreen()
    }
}
