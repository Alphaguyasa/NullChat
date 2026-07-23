package et.alpha.nchat.ui.permissions

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import et.alpha.nchat.R
import et.alpha.nchat.mesh.transport.RadioBlocker
import et.alpha.nchat.mesh.transport.RadioMonitor
import et.alpha.nchat.mesh.transport.RadioState
import et.alpha.nchat.ui.theme.NChatTheme

/**
 * Gates [content] behind the radios actually being switched on.
 *
 * Sits inside [PermissionGate], because the order matters: asking someone to
 * enable Bluetooth before they have granted permission to use it is asking them
 * to fix the wrong thing.
 *
 * Without this the failure is completely silent — Nearby logs an error every
 * scan, the notification reads "0 devices in range" forever, and nothing
 * anywhere tells the user that the fix is one toggle away.
 */
@Composable
fun RadioGate(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val monitor = remember { RadioMonitor(context) }
    // Seeded with a real sample so the first frame is correct rather than a
    // flash of "Bluetooth is off" on a device where it is on.
    val state by monitor.state.collectAsState(initial = monitor.current())

    val blockers = state.blockers
    if (blockers.isEmpty()) {
        content()
    } else {
        // No "continue anyway": the mesh genuinely cannot work, and letting the
        // user past here just returns them to the silent failure this exists to
        // replace. The screen dismisses itself when the toggle flips.
        RadioBlockedScreen(
            modifier = modifier,
            blockers = blockers,
            onEnableBluetooth = { context.requestBluetoothEnable() },
            onOpenLocationSettings = { context.openLocationSettings() },
        )
    }
}

/**
 * Scrollable for the same reason [PermissionGate]'s scaffold is: at 200% font
 * scale the two-blocker body plus both buttons is taller than a small phone, and
 * a centred column with no scroll would push the buttons off the bottom edge —
 * on the one screen whose entire job is offering those buttons.
 */
@Composable
private fun RadioBlockedScreen(
    blockers: List<RadioBlocker>,
    onEnableBluetooth: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.radio_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(blockers.bodyRes()),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))

        // One button per thing that is actually off, so there is never a control
        // here that does nothing.
        if (RadioBlocker.BLUETOOTH_OFF in blockers) {
            Button(onClick = onEnableBluetooth, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.radio_enable_bluetooth))
            }
            Spacer(Modifier.height(8.dp))
        }
        if (RadioBlocker.LOCATION_SERVICES_OFF in blockers) {
            OutlinedButton(onClick = onOpenLocationSettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.radio_open_location_settings))
            }
        }
    }
}

/** Explains exactly what is off, rather than a generic "something is wrong". */
private fun List<RadioBlocker>.bodyRes(): Int = when {
    size > 1 -> R.string.radio_body_both
    firstOrNull() == RadioBlocker.LOCATION_SERVICES_OFF -> R.string.radio_body_location
    else -> R.string.radio_body_bluetooth
}

/**
 * Asks the system to turn Bluetooth on.
 *
 * The system dialog is used rather than flipping the adapter directly: silently
 * enabling a radio on someone's phone is not ours to do, and direct enable was
 * removed outright in Android 13.
 */
private fun Context.requestBluetoothEnable() {
    runCatching {
        startActivity(
            Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun Context.openLocationSettings() {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

// Surface-wrapped so the preview inherits the theme's background and onBackground
// content color the way MainActivity's Scaffold does at runtime; a bare preview
// renders the text in the default black instead.
@Preview(showBackground = true)
@Composable
private fun BluetoothOffPreview() {
    NChatTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            RadioBlockedScreen(
                blockers = listOf(RadioBlocker.BLUETOOTH_OFF),
                onEnableBluetooth = {},
                onOpenLocationSettings = {},
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun BothOffPreview() {
    NChatTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            RadioBlockedScreen(
                blockers = RadioState.UNKNOWN.blockersOn(29),
                onEnableBluetooth = {},
                onOpenLocationSettings = {},
            )
        }
    }
}
