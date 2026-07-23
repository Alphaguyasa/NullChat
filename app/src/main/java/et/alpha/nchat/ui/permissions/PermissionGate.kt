package et.alpha.nchat.ui.permissions

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import et.alpha.nchat.R
import et.alpha.nchat.ui.theme.NChatTheme

/** Where the permission flow currently stands. */
sealed interface PermissionState {

    /** Everything essential is granted; the mesh can run. */
    data object Granted : PermissionState

    /** Not asked yet, or asked and soft-denied. Explain, then ask. */
    data class NeedsRationale(val previouslyDenied: Boolean) : PermissionState

    /**
     * The system will no longer show a prompt. Only Settings can fix this.
     *
     * Reached after two denials on modern Android, or one "don't ask again".
     */
    data object PermanentlyDenied : PermissionState
}

/**
 * Gates [content] behind the mesh's runtime permissions.
 *
 * Three states, because two is not enough: a user who has permanently denied a
 * permission gets no system prompt at all, so a screen that only knows
 * "granted / not granted" shows a button that visibly does nothing. That
 * distinction is the whole reason this is not a one-liner.
 */
@Composable
fun PermissionGate(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val activity = context.findActivity()

    var state by remember { mutableStateOf(context.evaluatePermissionState(askedBefore = false)) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val essential = MeshPermissions.essential
        val allGranted = essential.all { results[it] == true || context.isGranted(it) }

        state = when {
            allGranted -> PermissionState.Granted

            // The system stops showing rationale once it will stop prompting. We
            // know a prompt just happened, so "no rationale and still denied"
            // means the only remaining route is Settings.
            activity != null && essential.none { activity.shouldShowRationale(it) } ->
                PermissionState.PermanentlyDenied

            else -> PermissionState.NeedsRationale(previouslyDenied = true)
        }
    }

    when (val current = state) {
        is PermissionState.Granted -> content()

        is PermissionState.NeedsRationale -> RationaleScreen(
            modifier = modifier,
            previouslyDenied = current.previouslyDenied,
            onGrant = { launcher.launch(MeshPermissions.required.toTypedArray()) },
        )

        is PermissionState.PermanentlyDenied -> SettingsScreen(
            modifier = modifier,
            onOpenSettings = { context.openAppSettings() },
            onRecheck = { state = context.evaluatePermissionState(askedBefore = true) },
        )
    }
}

@Composable
private fun RationaleScreen(
    previouslyDenied: Boolean,
    onGrant: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GateScaffold(
        modifier = modifier,
        title = stringResource(R.string.permission_title),
        // A second ask gets a blunter explanation. Repeating the first wording
        // to someone who already said no is how apps get denied permanently.
        body = stringResource(
            if (previouslyDenied) R.string.permission_body_retry else R.string.permission_body,
        ),
    ) {
        Button(onClick = onGrant, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.permission_grant))
        }
    }
}

@Composable
private fun SettingsScreen(
    onOpenSettings: () -> Unit,
    onRecheck: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GateScaffold(
        modifier = modifier,
        title = stringResource(R.string.permission_blocked_title),
        body = stringResource(R.string.permission_blocked_body),
    ) {
        Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.permission_open_settings))
        }
        Spacer(Modifier.height(8.dp))
        // Returning from Settings does not recompose this screen on its own, so
        // the user needs a way to say "I fixed it" without restarting the app.
        OutlinedButton(onClick = onRecheck, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.permission_recheck))
        }
    }
}

/**
 * Centred title, explanation, and whatever action can fix it.
 *
 * Scrollable even though it is meant to fit on one screen. At 200% font scale
 * the longer rationale runs past the bottom of a small phone, and a centred
 * column with nowhere to scroll loses text off *both* ends — including the
 * button, which would leave the user unable to grant anything. `Center` still
 * centres it whenever it does fit.
 */
@Composable
private fun GateScaffold(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        actions()
    }
}

// region platform glue

private fun Context.isGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun Activity.shouldShowRationale(permission: String): Boolean =
    androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(this, permission)

private fun Context.evaluatePermissionState(askedBefore: Boolean): PermissionState {
    val essential = MeshPermissions.essential
    if (essential.all { isGranted(it) }) return PermissionState.Granted

    val activity = findActivity()
    return when {
        // Before the first request, shouldShowRationale is false for a reason
        // that has nothing to do with permanent denial, so it cannot be used to
        // distinguish the two without knowing whether we have asked yet.
        !askedBefore -> PermissionState.NeedsRationale(previouslyDenied = false)
        activity != null && essential.none { activity.shouldShowRationale(it) } ->
            PermissionState.PermanentlyDenied
        else -> PermissionState.NeedsRationale(previouslyDenied = true)
    }
}

private fun Context.openAppSettings() {
    startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is android.content.ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

// endregion

// Wrapped in a Surface so the preview inherits the theme's background and its
// onBackground content color, exactly as MainActivity's Scaffold supplies them
// at runtime. Without it a bare preview renders text in the default black, which
// is not what ships and hid nothing worth seeing.
@Preview(showBackground = true)
@Composable
private fun RationalePreview() {
    NChatTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            RationaleScreen(previouslyDenied = false, onGrant = {})
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun BlockedPreview() {
    NChatTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            SettingsScreen(onOpenSettings = {}, onRecheck = {})
        }
    }
}
