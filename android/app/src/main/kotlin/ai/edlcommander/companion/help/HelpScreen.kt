package ai.edlcommander.companion.help

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

const val MANUAL_ASSET = "MANUAL.md"

fun loadManual(context: Context): String = try {
    context.assets.open(MANUAL_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
} catch (_: java.io.IOException) {
    "# Manual unavailable\n\nThe bundled manual could not be read. See docs/MANUAL.md in the repository."
}

/** Offline Help screen: renders the manual bundled as an APK asset. */
@Composable
fun HelpScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val blocks = remember { ManualParser.parse(loadManual(context)) }
    LazyColumn(
        modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { OutlinedButton(onClick = onBack) { Text("Back") } }
        items(blocks) { b ->
            when (b) {
                is ManualBlock.Heading -> Text(
                    b.text,
                    style = when (b.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    },
                )
                is ManualBlock.Paragraph -> Text(b.text, style = MaterialTheme.typography.bodyMedium)
                is ManualBlock.Bullet -> Text(
                    "\u2022 " + b.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = (8 + 16 * b.indent).dp),
                )
                is ManualBlock.Code -> Text(b.text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
