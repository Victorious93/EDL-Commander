package ai.edlcommander.companion.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** Applies the current fleet palette (or the built-in default) to MaterialTheme, live. */
@Composable
fun FleetTheme(content: @Composable () -> Unit) {
    val p = FleetThemeClient.current.palette
    val bg = Color(p.background)
    val scheme = if (bg.luminance() < 0.5f) {
        darkColorScheme(
            primary = Color(p.primary), secondary = Color(p.secondary), background = bg,
            surface = Color(p.surface), onBackground = Color(p.onBackground), onSurface = Color(p.onBackground),
            onSurfaceVariant = Color(p.muted), surfaceVariant = Color(p.tile), error = Color(p.danger),
            tertiary = Color(p.stale),
        )
    } else {
        lightColorScheme(
            primary = Color(p.primary), secondary = Color(p.secondary), background = bg,
            surface = Color(p.surface), onBackground = Color(p.onBackground), onSurface = Color(p.onBackground),
            onSurfaceVariant = Color(p.muted), surfaceVariant = Color(p.tile), error = Color(p.danger),
            tertiary = Color(p.stale),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
