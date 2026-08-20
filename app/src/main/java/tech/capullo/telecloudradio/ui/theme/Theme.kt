package tech.capullo.telecloudradio.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import tech.capullo.audio.ui.capulloColorScheme

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80,
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40,
)

@Composable
fun TelecloudRadioTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    // Cover of the currently active track, if any. Its dominant hue is tinted into the accent and
    // surface roles so the whole app - player, mini player, sheets, station list - wears the
    // colours of what is playing. Null (nothing playing, or art-less track) leaves the base
    // scheme untouched, which is exactly the pre-existing look.
    albumArt: ByteArray? = null,
    content: @Composable () -> Unit,
) {
    val baseScheme = capulloColorScheme(
        darkTheme = darkTheme,
        lightColors = LightColorScheme,
        darkColors = DarkColorScheme,
        dynamicColor = dynamicColor,
    )
    val colorScheme = baseScheme.tintedBy(rememberArtSeed(albumArt), darkTheme)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
