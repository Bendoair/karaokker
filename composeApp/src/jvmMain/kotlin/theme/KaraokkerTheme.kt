package theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private fun c(hex: String): Color = Color(("FF$hex").toLong(16))

private val KaraokkerColorScheme = darkColorScheme(
    primary = c("95d77d"),
    onPrimary = c("083900"),
    primaryContainer = c("155206"),
    onPrimaryContainer = c("b0f496"),
    secondary = c("63dbb5"),
    onSecondary = c("00382a"),
    secondaryContainer = c("00513e"),
    onSecondaryContainer = c("81f8d0"),
    tertiary = c("b6d252"),
    onTertiary = c("2a3500"),
    tertiaryContainer = c("3d4c00"),
    onTertiaryContainer = c("d1ef6b"),
    error = c("ffb4ab"),
    onError = c("690005"),
    errorContainer = c("93000a"),
    onErrorContainer = c("ffdad6"),
    background = c("1a1c18"),
    onBackground = c("e3e3dc"),
    surface = c("1a1c18"),
    onSurface = c("e3e3dc"),
    surfaceVariant = c("292b26"),
    onSurfaceVariant = c("c3c8bc"),
    outline = c("8d9387"),
    outlineVariant = c("43483f"),
    inverseSurface = c("e3e3dc"),
    inverseOnSurface = c("2f312d"),
    inversePrimary = c("2f6b1f"),
    scrim = c("000000"),
    surfaceDim = c("121410"),
    surfaceBright = c("383a35"),
    surfaceContainerLowest = c("0d0f0b"),
    surfaceContainerLow = c("1a1c18"),
    surfaceContainer = c("1e201c"),
    surfaceContainerHigh = c("292b26"),
    surfaceContainerHighest = c("333531"),
)

@Composable
fun KaraokkerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = KaraokkerColorScheme,
        content = content,
    )
}
