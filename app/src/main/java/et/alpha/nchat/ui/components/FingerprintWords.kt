package et.alpha.nchat.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import et.alpha.nchat.ui.theme.MonoFingerprint
import et.alpha.nchat.ui.theme.NChatTheme

/**
 * A key fingerprint as words, sized to be read out loud.
 *
 * The words exist because comparing 64 hex characters over a noisy room does not
 * happen — people skim, agree, and skip the check that the whole verification
 * model rests on. Words survive being spoken.
 *
 * Laid out two per line rather than in one long row, and one per line once the
 * user's font scale passes 150%. A fingerprint that wraps mid-word is a
 * fingerprint nobody will finish reading, and at 200% scale a word like
 * "juniper" needs about 200dp of 48sp monospace — wider than half a phone, so
 * two columns broke words across lines exactly where it matters most. The column
 * count gives way instead; the block just gets taller.
 *
 * The whole block is announced as one comma-separated string, so a screen
 * reader pauses between words instead of running them together.
 */
@Composable
fun FingerprintWords(
    words: List<String>,
    modifier: Modifier = Modifier,
    wordsPerRow: Int = if (LocalDensity.current.fontScale >= SingleColumnFontScale) 1 else 2,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = words.joinToString(", ") },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        words.chunked(wordsPerRow.coerceAtLeast(1)).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                row.forEach { word ->
                    Text(
                        text = word,
                        style = MonoFingerprint,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
                // Keeps a short final row's words in the same columns as the
                // rows above, so the block still reads as a grid.
                repeat(wordsPerRow - row.size) {
                    Text(text = "", modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** Where two columns of 24sp monospace stop fitting side by side on a phone. */
private const val SingleColumnFontScale = 1.5f

@Preview(showBackground = true, backgroundColor = 0xFF0E1A26)
@Composable
private fun FingerprintWordsPreview() {
    NChatTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            FingerprintWords(
                words = listOf("harbor", "cobalt", "ripple", "anthem", "juniper", "static"),
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0E1A26, name = "Odd word count")
@Composable
private fun FingerprintWordsOddPreview() {
    NChatTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            FingerprintWords(
                words = listOf("harbor", "cobalt", "ripple", "anthem", "juniper"),
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}
