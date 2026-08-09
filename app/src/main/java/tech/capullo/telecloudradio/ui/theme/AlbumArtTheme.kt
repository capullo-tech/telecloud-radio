package tech.capullo.telecloudradio.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Album-art driven theming. The base scheme (wallpaper dynamic colour on S+, else the static
// Purple fallback) stays the source of truth for structure and contrast; the art only supplies a
// hue, which is re-tinted into the accent + surface roles. Deriving a WHOLE scheme from the art
// would fight the OS dynamic colour the app already opts into, and a full tonal-palette generator
// is not public API in Compose Material3 (androidx.compose.material3.internal.colorUtil is
// internal), so the choice is between a third-party port and this. Tinting the base keeps the
// diff honest: no art -> byte-identical to the previous look.

// Art is downsampled to roughly this before quantising - Palette's own cost scales with pixel
// count and album art is routinely 1000px+ square, which is pure waste for picking one hue.
private const val PALETTE_TARGET_PX = 128

// Palette buckets the image into this many colours before scoring. 24 is enough to separate a
// cover's accent from its background without paying for a fine-grained histogram.
private const val PALETTE_COLOR_COUNT = 24

// Art hues are clamped into a usable band: below the floor the tint reads as accidental grey,
// above the ceiling saturated covers produce a neon UI that fights the content.
private const val MIN_SATURATION = 0.30f
private const val MAX_SATURATION = 0.85f

// Minimum contrast of the accent against the surface it sits on. 3.0 is the WCAG floor for large
// text and UI components, which is what the accent is used for (play button, seek bar, icons).
private const val MIN_ACCENT_CONTRAST = 3.0

// Track changes cross-fade rather than snap. Long enough to read as a transition, short enough
// that the recomposition it drives stays inside the change the track switch already caused.
private const val TINT_ANIM_MS = 450

/**
 * Extracts a seed colour from [albumArt], off the main thread.
 *
 * Keyed on the array instance: cover bytes are replaced wholesale per track and `ByteArray`
 * equality is identity, so a new cover restarts the effect and a re-emission of the same one
 * does not.
 */
@Composable
internal fun rememberArtSeed(albumArt: ByteArray?): Color? {
    val seed by produceState<Color?>(initialValue = null, albumArt) {
        value = albumArt?.let { bytes -> withContext(Dispatchers.Default) { extractSeed(bytes) } }
    }
    return seed
}

private fun extractSeed(bytes: ByteArray): Color? {
    val bitmap = decodeDownsampled(bytes) ?: return null
    return try {
        val palette = Palette.from(bitmap).maximumColorCount(PALETTE_COLOR_COUNT).generate()
        // Vibrant first - it is the closest thing to "the colour someone would name if asked what
        // this cover looks like". The muted/dominant fallbacks catch monochrome and washed-out art
        // that has no vibrant swatch at all.
        val swatch = palette.vibrantSwatch
            ?: palette.lightVibrantSwatch
            ?: palette.darkVibrantSwatch
            ?: palette.mutedSwatch
            ?: palette.dominantSwatch
        swatch?.let { Color(it.rgb) }
    } catch (_: IllegalArgumentException) {
        null
    } finally {
        bitmap.recycle()
    }
}

private fun decodeDownsampled(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val longestSide = maxOf(bounds.outWidth, bounds.outHeight)
    if (longestSide <= 0) return null
    val opts = BitmapFactory.Options().apply {
        // inSampleSize is honoured only at powers of two, so round down to one.
        inSampleSize = Integer.highestOneBit((longestSide / PALETTE_TARGET_PX).coerceAtLeast(1))
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}

/**
 * Re-tints the accent and surface roles of this scheme towards [seed], then animates the result so
 * a track change cross-fades instead of snapping. Roles carrying text contrast (`onSurface`,
 * `onBackground`, `error*`) are left alone - the base scheme already guarantees them.
 */
@Composable
internal fun ColorScheme.tintedBy(seed: Color, darkTheme: Boolean): ColorScheme = withSeed(seed, darkTheme).animated()

private fun ColorScheme.withSeed(seed: Color, dark: Boolean): ColorScheme {
    val hsl = seed.toHsl()
    val hue = hsl[0]
    val sat = hsl[1].coerceIn(MIN_SATURATION, MAX_SATURATION)

    // The colour that surfaces are pulled towards. Mid-lightness so the blend shifts hue without
    // lightening or darkening the surface ramp the base scheme established.
    val accent = hslColor(hue, sat, if (dark) 0.55f else 0.50f)
    fun tint(base: Color, amount: Float) = lerp(base, accent, amount)

    val primary = hslColor(hue, sat, if (dark) 0.72f else 0.40f)
        .ensureContrast(against = tint(surface, SURFACE_TINT), dark = dark)

    return copy(
        primary = primary,
        onPrimary = hslColor(hue, sat * 0.5f, if (dark) 0.10f else 0.99f),
        primaryContainer = hslColor(hue, sat * 0.85f, if (dark) 0.26f else 0.88f),
        onPrimaryContainer = hslColor(hue, sat * 0.5f, if (dark) 0.92f else 0.14f),
        secondary = hslColor(hue, sat * 0.45f, if (dark) 0.74f else 0.42f),
        onSecondary = hslColor(hue, sat * 0.4f, if (dark) 0.12f else 0.99f),
        secondaryContainer = hslColor(hue, sat * 0.4f, if (dark) 0.24f else 0.90f),
        onSecondaryContainer = hslColor(hue, sat * 0.3f, if (dark) 0.90f else 0.16f),
        // Tertiary gets an analogous hue so accents that need to read as "a different colour"
        // (chips, secondary highlights) still belong to the cover rather than the old palette.
        tertiary = hslColor((hue + 60f) % 360f, sat * 0.7f, if (dark) 0.74f else 0.42f),
        onTertiary = hslColor((hue + 60f) % 360f, sat * 0.4f, if (dark) 0.12f else 0.99f),
        background = tint(background, SURFACE_TINT),
        surface = tint(surface, SURFACE_TINT),
        surfaceVariant = tint(surfaceVariant, CONTAINER_TINT),
        surfaceContainerLowest = tint(surfaceContainerLowest, SURFACE_TINT),
        surfaceContainerLow = tint(surfaceContainerLow, CONTAINER_TINT),
        surfaceContainer = tint(surfaceContainer, CONTAINER_TINT),
        surfaceContainerHigh = tint(surfaceContainerHigh, CONTAINER_TINT),
        surfaceContainerHighest = tint(surfaceContainerHighest, CONTAINER_TINT),
        onSurfaceVariant = tint(onSurfaceVariant, TEXT_TINT),
        outline = tint(outline, OUTLINE_TINT),
        outlineVariant = tint(outlineVariant, OUTLINE_TINT),
        surfaceTint = primary,
    )
}

private const val SURFACE_TINT = 0.06f
private const val CONTAINER_TINT = 0.12f
private const val TEXT_TINT = 0.10f
private const val OUTLINE_TINT = 0.15f

@Composable
private fun ColorScheme.animated(): ColorScheme = copy(
    primary = animatedColor(primary),
    onPrimary = animatedColor(onPrimary),
    primaryContainer = animatedColor(primaryContainer),
    onPrimaryContainer = animatedColor(onPrimaryContainer),
    secondary = animatedColor(secondary),
    onSecondary = animatedColor(onSecondary),
    secondaryContainer = animatedColor(secondaryContainer),
    onSecondaryContainer = animatedColor(onSecondaryContainer),
    tertiary = animatedColor(tertiary),
    onTertiary = animatedColor(onTertiary),
    background = animatedColor(background),
    surface = animatedColor(surface),
    surfaceVariant = animatedColor(surfaceVariant),
    surfaceContainerLowest = animatedColor(surfaceContainerLowest),
    surfaceContainerLow = animatedColor(surfaceContainerLow),
    surfaceContainer = animatedColor(surfaceContainer),
    surfaceContainerHigh = animatedColor(surfaceContainerHigh),
    surfaceContainerHighest = animatedColor(surfaceContainerHighest),
    onSurfaceVariant = animatedColor(onSurfaceVariant),
    outline = animatedColor(outline),
    outlineVariant = animatedColor(outlineVariant),
    surfaceTint = animatedColor(surfaceTint),
)

@Composable
private fun animatedColor(target: Color): Color = animateColorAsState(targetValue = target, animationSpec = tween(TINT_ANIM_MS), label = "artTint").value

// Walks lightness away from the background until the accent clears MIN_ACCENT_CONTRAST. Covers
// whose hue lands close to the surface (a dark navy cover on a dark theme) would otherwise hand
// back a play button that is nearly invisible.
private fun Color.ensureContrast(against: Color, dark: Boolean): Color {
    val hsl = toHsl()
    var lightness = hsl[2]
    var candidate = this
    val step = if (dark) 0.02f else -0.02f
    var guard = 0
    while (
        ColorUtils.calculateContrast(candidate.toArgb(), against.toArgb()) < MIN_ACCENT_CONTRAST &&
        guard++ < CONTRAST_STEPS &&
        lightness > 0f && lightness < 1f
    ) {
        lightness = (lightness + step).coerceIn(0f, 1f)
        candidate = hslColor(hsl[0], hsl[1], lightness)
    }
    return candidate
}

private const val CONTRAST_STEPS = 30

private fun Color.toHsl(): FloatArray = FloatArray(3).also { ColorUtils.colorToHSL(toArgb(), it) }

private fun hslColor(hue: Float, saturation: Float, lightness: Float): Color = Color(ColorUtils.HSLToColor(floatArrayOf(hue, saturation.coerceIn(0f, 1f), lightness.coerceIn(0f, 1f))))
