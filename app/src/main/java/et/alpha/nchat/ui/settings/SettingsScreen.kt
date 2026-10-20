package et.alpha.nchat.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import et.alpha.nchat.R
import et.alpha.nchat.mesh.protocol.Profile
import et.alpha.nchat.ui.theme.Danger
import et.alpha.nchat.ui.theme.MonoBody
import et.alpha.nchat.ui.theme.MonoSmall
import et.alpha.nchat.ui.theme.NChatTheme
import et.alpha.nchat.ui.theme.TextMuted

/**
 * The fourth destination: what this device calls itself.
 *
 * The display name, the device id and the panic wipe. The screen exists as a
 * place for the settings the roadmap lines up behind them (battery modes, app
 * lock), so it is a scrolling column of sections rather than a dialog hung off
 * the peer list.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val saved by viewModel.saved.collectAsState()
    val wipeFailed by viewModel.wipeFailed.collectAsState()

    SettingsContent(
        state = state,
        saved = saved,
        wipeFailed = wipeFailed,
        onBack = onBack,
        onDraftChange = viewModel::onDraftChange,
        onSave = viewModel::onSave,
        onWipe = viewModel::onWipe,
        modifier = modifier,
    )
}

/** Stateless body, so it can be previewed and tested without Hilt. */
@Composable
internal fun SettingsContent(
    state: SettingsState,
    saved: Boolean,
    wipeFailed: Boolean,
    onBack: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    onWipe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmWipe by remember { mutableStateOf(false) }
    if (confirmWipe) {
        WipeDialog(
            onConfirm = {
                confirmWipe = false
                onWipe()
            },
            onDismiss = { confirmWipe = false },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 16.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A glyph, as on the chat screen: material-icons is not a dependency.
            IconButton(onClick = onBack) {
                Text(
                    text = "←",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.clearAndSetSemantics {
                        contentDescription = "Back to peers"
                    },
                )
            }
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        HorizontalDivider()

        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_name_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            OutlinedTextField(
                value = state.draft,
                onValueChange = onDraftChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge,
                placeholder = {
                    Text(
                        text = stringResource(R.string.settings_name_placeholder),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                supportingText = {
                    // Bytes, as in the send box: the limit is what fits in a packet,
                    // and Ge'ez letters cost three each.
                    Text(
                        text = "${state.draftBytes}/${Profile.MAX_NAME_BYTES} bytes",
                        style = MonoSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { if (state.canSave) onSave() }),
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // Said plainly, because a name that travels over the air to
                    // strangers is not the same as a name kept on the phone.
                    text = if (saved && !state.canSave) {
                        stringResource(R.string.settings_name_saved)
                    } else {
                        stringResource(R.string.settings_name_hint)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onSave, enabled = state.canSave) {
                    Text(text = stringResource(R.string.settings_name_save))
                }
            }

            HorizontalDivider()

            Text(
                text = stringResource(R.string.settings_id_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // The full id here, unlike the eight characters elsewhere: this is the
            // one screen about who this device is, and it never goes in a log.
            Text(text = state.selfId, style = MonoBody, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = stringResource(R.string.settings_id_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
            )

            HorizontalDivider()

            Text(
                text = stringResource(R.string.settings_wipe_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (wipeFailed) {
                    stringResource(R.string.settings_wipe_failed)
                } else {
                    stringResource(R.string.settings_wipe_hint)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (wipeFailed) Danger else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { confirmWipe = true }) {
                Text(text = stringResource(R.string.settings_wipe_action), color = Danger)
            }
        }
    }
}

/**
 * The one step between the button and the wipe.
 *
 * Exactly one: a panic wipe that takes typing a phrase is one nobody finishes
 * in a hurry, and a button with no confirmation is one a pocket can press. The
 * body says what is lost and what other phones will see, because "delete" on a
 * mesh normally means this phone only, and here it also means the identity.
 */
@Composable
private fun WipeDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_wipe_confirm_title)) },
        text = { Text(stringResource(R.string.settings_wipe_confirm_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.settings_wipe_confirm), color = Danger)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_wipe_cancel)) }
        },
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF0E1A26)
@Composable
private fun SettingsPreview() {
    NChatTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            SettingsContent(
                state = SettingsState(selfId = "1ac3f00d9e8b7a65", savedName = "Maya", draft = "Maya T"),
                saved = false,
                wipeFailed = false,
                onBack = {},
                onDraftChange = {},
                onSave = {},
                onWipe = {},
            )
        }
    }
}
