package et.alpha.nchat.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Dynamic color is deliberately absent. Material You would repaint the mesh
// green with whatever is on the user's wallpaper, and in this app the accent
// carries meaning — it is the color of "connected" and of an outgoing message.

private val DarkColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = BgBase,
    primaryContainer = AccentDim,
    onPrimaryContainer = TextPrimary,
    inversePrimary = AccentLight,

    secondary = AccentBright,
    onSecondary = BgBase,
    secondaryContainer = BgElevated,
    onSecondaryContainer = TextPrimary,

    tertiary = AccentDim,
    onTertiary = TextPrimary,
    tertiaryContainer = BgSurface,
    onTertiaryContainer = TextSecondary,

    background = BgBase,
    onBackground = TextPrimary,

    surface = BgBase,
    onSurface = TextPrimary,
    surfaceVariant = BgSurface,
    onSurfaceVariant = TextSecondary,
    surfaceTint = Accent,
    inverseSurface = TextPrimary,
    inverseOnSurface = BgBase,

    // Material's container ramp normally climbs by elevation tint. Here it maps
    // onto the three flat background tokens instead: base, surface, elevated.
    surfaceContainerLowest = BgBase,
    surfaceContainerLow = BgBase,
    surfaceContainer = BgSurface,
    surfaceContainerHigh = BgSurface,
    surfaceContainerHighest = BgElevated,
    surfaceBright = BgElevated,
    surfaceDim = BgBase,

    error = Danger,
    onError = TextPrimary,
    errorContainer = BgSurface,
    onErrorContainer = Danger,

    outline = AccentDim,
    outlineVariant = Hairline,
    scrim = Color.Black,
)

// The light palette is a courtesy for daylight use; the dark one above is the
// app. Only four light tokens are specified, so the rest are composed from them
// rather than invented.
private val LightColorScheme = lightColorScheme(
    primary = AccentLight,
    onPrimary = BgSurfaceLight,
    primaryContainer = AccentBright,
    onPrimaryContainer = TextPrimaryL,
    inversePrimary = Accent,

    secondary = AccentDim,
    onSecondary = BgSurfaceLight,
    secondaryContainer = AccentBright,
    onSecondaryContainer = TextPrimaryL,

    tertiary = AccentLight,
    onTertiary = BgSurfaceLight,
    tertiaryContainer = BgSurfaceLight,
    onTertiaryContainer = TextPrimaryL,

    background = BgBaseLight,
    onBackground = TextPrimaryL,

    surface = BgBaseLight,
    onSurface = TextPrimaryL,
    surfaceVariant = BgSurfaceLight,
    onSurfaceVariant = TextPrimaryL.copy(alpha = 0.66f),
    surfaceTint = AccentLight,
    inverseSurface = TextPrimaryL,
    inverseOnSurface = BgBaseLight,

    surfaceContainerLowest = BgSurfaceLight,
    surfaceContainerLow = BgSurfaceLight,
    surfaceContainer = BgSurfaceLight,
    surfaceContainerHigh = BgSurfaceLight,
    surfaceContainerHighest = BgBaseLight,
    surfaceBright = BgSurfaceLight,
    surfaceDim = BgBaseLight,

    error = Danger,
    onError = BgSurfaceLight,
    errorContainer = BgSurfaceLight,
    onErrorContainer = Danger,

    outline = AccentLight,
    outlineVariant = AccentLight.copy(alpha = 0.2f),
    scrim = Color.Black,
)

/**
 * There is no `dynamicColor` parameter: dynamic color is off, permanently, so
 * there is nothing to switch on.
 *
 * @param darkTheme dark unless a caller explicitly asks otherwise. This does not
 *   follow the system setting: the app is used at night, at protests and in
 *   power cuts, so dark is the product, not a preference.
 */
@Composable
fun NChatTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    // enableEdgeToEdge() picks system-bar icon colors from the system's own
    // light/dark setting. Since the theme ignores that setting, the icons have
    // to be corrected here or they end up dark-on-dark on a phone in light mode.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
