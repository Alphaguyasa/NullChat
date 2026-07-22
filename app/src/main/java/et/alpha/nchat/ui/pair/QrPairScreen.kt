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
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import et.alpha.nchat.R
import java.util.concurrent.Executors

/**
 * Pairing: show my code, scan theirs.
 *
 * Scanning is what makes a contact *verified*. Keys also arrive over the radio
 * in ANNOUNCE packets, but those are trust-on-first-use — nothing ties them to a
 * person. A code read off a screen the user is holding does.
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
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.pair_title)) },
                navigationIcon = {
                    // Glyph rather than a vector asset: material-icons is not in
                    // the Compose BOM here, matching ChatScreen.
                    IconButton(onClick = onBack) {
                        Text(text = "←", style = MaterialTheme.typography.titleLarge)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MyCode(uri = state.pairingUri, fingerprint = state.fingerprint, shortId = state.shortId)

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Scanner(outcome = state.outcome, onScanned = viewModel::onScanned, onReset = viewModel::reset)
        }
    }
}

@Composable
private fun MyCode(uri: String, fingerprint: List<String>, shortId: String) {
    Text(
        text = stringResource(R.string.pair_my_code),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp),
    )

    if (uri.isNotEmpty()) {
        val bitmap = remember(uri) { qrBitmap(uri, QR_SIZE_PX) }
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.pair_my_code_description),
            modifier = Modifier
                .padding(top = 12.dp)
                .fillMaxWidth(0.7f)
                .aspectRatio(1f),
        )
    }

    Text(
        text = "You: ${shortId.take(SHORT_ID_CHARS)}",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )

    Text(
        text = fingerprint.joinToString(" "),
        style = MaterialTheme.typography.titleMedium,
        fontFamily = FontFamily.Monospace,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 8.dp),
    )
    Text(
        text = stringResource(R.string.pair_fingerprint_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 4.dp),
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
    )

    when (outcome) {
        is ScanOutcome.Paired -> PairedResult(outcome, onReset)

        else -> {
            if (granted) {
                CameraPreview(onScanned = onScanned)
            } else {
                Text(
                    text = stringResource(R.string.pair_camera_rationale),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                Button(
                    onClick = { launcher.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.pair_camera_grant))
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
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun PairedResult(paired: ScanOutcome.Paired, onReset: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.pair_verified, paired.shortId.take(SHORT_ID_CHARS)),
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
        )
        Text(
            text = paired.fingerprint.joinToString(" "),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.pair_compare_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.pair_scan_another))
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
            .padding(vertical = 12.dp)
            .fillMaxWidth()
            .aspectRatio(1f),
    ) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
    }
}

private const val QR_SIZE_PX = 512
private const val SHORT_ID_CHARS = 8
