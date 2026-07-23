package et.alpha.nchat.ui.pair

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview as PreviewAnnotation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import et.alpha.nchat.R
import et.alpha.nchat.ui.components.FingerprintWords
import et.alpha.nchat.ui.theme.AccentBright
import et.alpha.nchat.ui.theme.BgBase
import et.alpha.nchat.ui.theme.Hairline
import et.alpha.nchat.ui.theme.MonoSmall
import et.alpha.nchat.ui.theme.NChatTheme
import et.alpha.nchat.ui.theme.motionSpec
import et.alpha.nchat.ui.theme.rememberAnimationsEnabled
import java.util.concurrent.Executors

/**
 * Pairing: show my code, scan theirs.
 *
 * Scanning is what makes a contact *verified*. Keys also arrive over the radio
 * in ANNOUNCE packets, but those are trust-on-first-use — nothing ties them to a
 * person. A code read off a screen the user is holding does.
 *
 * Full-bleed: no cards, no panels, no bar under the top bar. Both halves of the
 * screen are something a second person is looking at across a table, and framing
 * either one in a surface would only shrink it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrPairScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: QrPairViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.pair_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                },
                navigationIcon = {
                    // Glyph rather than a vector asset: material-icons is not in
                    // the Compose BOM here, matching ChatScreen.
                    IconButton(onClick = onBack) {
                        Text(
                            text = "←",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MyCode(uri = state.pairingUri, fingerprint = state.fingerprint, shortId = state.shortId)

            HorizontalDivider(modifier = Modifier.padding(vertical = 28.dp))

            Scanner(outcome = state.outcome, onScanned = viewModel::onScanned, onReset = viewModel::reset)

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MyCode(uri: String, fingerprint: List<String>, shortId: String) {
    Text(
        text = stringResource(R.string.pair_my_code),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 8.dp),
    )

    if (uri.isNotEmpty()) {
        // AccentBright on BgBase, per the design system, rather than the
        // conventional black on white. The background is the page colour, so the
        // code has no edge and the quiet zone is effectively the rest of the
        // screen — which is why `QR_MARGIN_MODULES` can stay at 1. Our scanner
        // decodes inverted codes; see `readQrText`.
        val bitmap = remember(uri) {
            qrBitmap(
                text = uri,
                sizePx = QR_SIZE_PX,
                moduleColor = AccentBright.toArgb(),
                backgroundColor = BgBase.toArgb(),
            )
        }
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.pair_my_code_description),
            modifier = Modifier
                .padding(top = 20.dp)
                .background(BgBase)
                .fillMaxWidth(0.78f)
                .widthIn(max = 320.dp)
                .aspectRatio(1f),
            // The bitmap is one pixel per module and is drawn several times that
            // size. Default filtering smears the module edges into a grey mush
            // that a camera then has to guess at; None keeps them square.
            filterQuality = FilterQuality.None,
        )
    }

    Text(
        text = "You: ${shortId.take(SHORT_ID_CHARS)}",
        style = MonoSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 20.dp),
    )

    // Six words, large and spaced, for two people to read to each other out loud.
    FingerprintWords(
        words = fingerprint,
        modifier = Modifier.padding(top = 20.dp),
    )

    Text(
        text = stringResource(R.string.pair_fingerprint_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 16.dp),
    )
}

@Composable
private fun Scanner(
    outcome: ScanOutcome,
    onScanned: (String) -> Unit,
    onReset: () -> Unit,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }

    Text(
        text = stringResource(R.string.pair_scan_title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )

    // Held so the result is still renderable while it fades out, by which point
    // `reset` has already cleared the outcome that produced it.
    var lastPaired by remember { mutableStateOf<ScanOutcome.Paired?>(null) }
    LaunchedEffect(outcome) { (outcome as? ScanOutcome.Paired)?.let { lastPaired = it } }

    // Keyed on "is there a result" rather than on the outcome itself, so the
    // scanner does not fade out and back in every time a wrong code is read —
    // the camera stays put and only the message under it changes.
    Crossfade(
        targetState = outcome is ScanOutcome.Paired,
        animationSpec = motionSpec(rememberAnimationsEnabled()),
        label = "scan outcome",
    ) { isPaired ->
        if (isPaired) {
            val paired = outcome as? ScanOutcome.Paired ?: lastPaired
            if (paired != null) PairedResult(paired, onReset)
        } else {
            // A Crossfade lays its content out in a Box, which would stack the
            // viewfinder and the message it explains on top of each other. The
            // Column restores the flow the enclosing screen had.
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (granted) {
                    CameraPreview(onScanned = onScanned)
                } else {
                    Text(
                        text = stringResource(R.string.pair_camera_rationale),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                    Button(
                        onClick = { launcher.launch(Manifest.permission.CAMERA) },
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.pair_camera_grant),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }

                val message = when (outcome) {
                    ScanOutcome.Ourselves -> stringResource(R.string.pair_scanned_self)
                    ScanOutcome.NotAPairingCode -> stringResource(R.string.pair_scanned_unknown)
                    else -> null
                }
                if (message != null) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PairedResult(paired: ScanOutcome.Paired, onReset: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.pair_verified, paired.shortId.take(SHORT_ID_CHARS)),
            style = MonoSmall,
            // The one moment in the app where the accent means "this is settled",
            // so it is spent here rather than on chrome.
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
        // Their fingerprint gets the same treatment as ours above: the comparison
        // is between two blocks of words, and it only works if they look alike.
        FingerprintWords(words = paired.fingerprint)
        Text(
            text = stringResource(R.string.pair_compare_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onReset,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.pair_scan_another),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/**
 * The camera feed, with every frame offered to the QR decoder.
 *
 * `KEEP_ONLY_LATEST` because decoding is slower than the frame rate and a
 * backlog would make the scanner feel seconds behind what the camera is
 * pointing at. Frames that contain no code are the overwhelming majority and
 * cost one failed decode each.
 */
@Composable
private fun CameraPreview(onScanned: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember { PreviewView(context) }

    LaunchedEffect(Unit) {
        val provider = ProcessCameraProvider.getInstance(context).get()
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(executor) { frame -> frame.use { f -> f.readQrText()?.let(onScanned) } } }

        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }
    }

    Box(
        modifier = Modifier
            .padding(top = 20.dp)
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(18.dp))
            // No shadow to lift the viewfinder off the page, so the hairline is
            // what says where the camera stops and the screen starts.
            .border(1.dp, Hairline, RoundedCornerShape(18.dp)),
    ) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
    }
}

private const val QR_SIZE_PX = 512
private const val SHORT_ID_CHARS = 8

private val PREVIEW_WORDS =
    listOf("harbor", "cobalt", "ripple", "anthem", "juniper", "static")

/** The half of the screen the other person is looking at. */
@PreviewAnnotation(showBackground = true, backgroundColor = 0xFF0E1A26)
@Composable
private fun MyCodePreview() {
    NChatTheme {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MyCode(
                uri = "nchat://pair?k=3QpVn8yZtLxWq2RfJ7dMcH5aB0eSgU4iYnO1kPzXvT6w",
                fingerprint = PREVIEW_WORDS,
                shortId = "1ac3f00d",
            )
        }
    }
}

@PreviewAnnotation(showBackground = true, backgroundColor = 0xFF0E1A26, name = "Paired")
@Composable
private fun PairedResultPreview() {
    NChatTheme {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PairedResult(
                paired = ScanOutcome.Paired(shortId = "9e8b7a65", fingerprint = PREVIEW_WORDS),
                onReset = {},
            )
        }
    }
}
