package com.localassistant.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Wrapping a shared palette token in a Compose colour.
 *
 * The palette is stored as plain ARGB integers (see [Palette]) because the wake
 * card is drawn by the system, which has no Compose to ask for a colour object.
 * The Int overload is the one that reads a bare ARGB value: the ULong overload
 * expects androidx's own packed form, whose high bits name a colour space.
 */
private fun paint(argb: Int): Color = Color(argb)

// Gruvbox Dark colors (gray background variant, dark mode, Gruvbox accents preserved)
private val GruvboxDarkPrimary = paint(GRUVBOX_PRIMARY)
private val GruvboxDarkSecondary = paint(GRUVBOX_SECONDARY)
private val GruvboxDarkTertiary = paint(GRUVBOX_TERTIARY)
private val GruvboxDarkBackground = paint(GRUVBOX_BACKGROUND)
private val GruvboxDarkSurface = paint(GRUVBOX_SURFACE)
private val GruvboxDarkSurfaceLow = paint(GRUVBOX_SURFACE_LOW)
private val GruvboxDarkSurfaceMed = paint(GRUVBOX_SURFACE_MED)
private val GruvboxDarkSurfaceHigh = paint(GRUVBOX_SURFACE_HIGH)

// Gruvbox Light colors (fallback palette)
private val GruvboxLightPrimary = paint(GRUVBOX_LIGHT_PRIMARY)
private val GruvboxLightSecondary = paint(GRUVBOX_LIGHT_SECONDARY)
private val GruvboxLightTertiary = paint(GRUVBOX_LIGHT_TERTIARY)
private val GruvboxLightBackground = paint(GRUVBOX_LIGHT_BACKGROUND)
private val GruvboxLightSurface = paint(GRUVBOX_LIGHT_SURFACE)

/**
 * Gruvbox Dark color scheme.
 */
val GruvboxDarkColorScheme = darkColorScheme(
    primary = GruvboxDarkPrimary,
    onPrimary = GruvboxDarkBackground,
    primaryContainer = paint(GRUVBOX_PRIMARY_CONTAINER),
    onPrimaryContainer = paint(GRUVBOX_ON_PRIMARY_CONTAINER),

    secondary = GruvboxDarkSecondary,
    onSecondary = GruvboxDarkBackground,
    secondaryContainer = paint(GRUVBOX_SECONDARY_CONTAINER),
    onSecondaryContainer = paint(GRUVBOX_ON_SECONDARY_CONTAINER),

    tertiary = GruvboxDarkTertiary,
    onTertiary = GruvboxDarkSurface,
    tertiaryContainer = paint(GRUVBOX_TERTIARY_CONTAINER),
    onTertiaryContainer = paint(GRUVBOX_ON_TERTIARY_CONTAINER),

    background = GruvboxDarkBackground,
    onBackground = paint(GRUVBOX_ON_BACKGROUND),

    surface = GruvboxDarkSurface,
    onSurface = paint(GRUVBOX_ON_SURFACE),

    surfaceVariant = GruvboxDarkSurfaceLow,
    onSurfaceVariant = paint(GRUVBOX_ON_SURFACE_MUTED),

    error = paint(GRUVBOX_ERROR),
    onError = GruvboxDarkBackground,

    surfaceDim = GruvboxDarkSurfaceLow,
    surfaceBright = GruvboxDarkSurfaceMed,
    surfaceContainerLow = GruvboxDarkSurfaceLow,
    surfaceContainer = GruvboxDarkSurfaceMed,
    surfaceContainerHigh = GruvboxDarkSurfaceHigh,
    surfaceContainerHighest = paint(GRUVBOX_SURFACE_HIGHEST),
)

/**
 * Gruvbox Light color scheme (fallback).
 */
val GruvboxLightColorScheme = lightColorScheme(
    primary = GruvboxLightPrimary,
    onPrimary = paint(GRUVBOX_LIGHT_ON_PRIMARY),
    primaryContainer = paint(GRUVBOX_LIGHT_PRIMARY_CONTAINER),
    onPrimaryContainer = paint(GRUVBOX_LIGHT_ON_PRIMARY),

    secondary = GruvboxLightSecondary,
    onSecondary = paint(GRUVBOX_LIGHT_ON_SECONDARY),
    secondaryContainer = paint(GRUVBOX_LIGHT_SECONDARY_CONTAINER),
    onSecondaryContainer = paint(GRUVBOX_LIGHT_ON_PRIMARY),

    tertiary = GruvboxLightTertiary,
    onTertiary = paint(GRUVBOX_LIGHT_ON_TERTIARY),
    tertiaryContainer = paint(GRUVBOX_LIGHT_TERTIARY_CONTAINER),
    onTertiaryContainer = paint(GRUVBOX_LIGHT_ON_PRIMARY),

    background = GruvboxLightBackground,
    onBackground = paint(GRUVBOX_LIGHT_ON_BACKGROUND),

    surface = GruvboxLightSurface,
    onSurface = paint(GRUVBOX_LIGHT_ON_BACKGROUND),

    surfaceVariant = paint(GRUVBOX_LIGHT_SURFACE_VARIANT),
    onSurfaceVariant = GruvboxLightTertiary,

    error = paint(GRUVBOX_LIGHT_ERROR),
    onError = GruvboxLightSurface,

    surfaceDim = paint(GRUVBOX_LIGHT_SURFACE_DIM),
    surfaceBright = GruvboxLightSurface,
    surfaceContainerLow = paint(GRUVBOX_LIGHT_SURFACE_CONTAINER_LOW),
    surfaceContainer = paint(GRUVBOX_LIGHT_SURFACE_CONTAINER),
    surfaceContainerHigh = paint(GRUVBOX_LIGHT_SURFACE_CONTAINER_HIGH),
    surfaceContainerHighest = paint(GRUVBOX_LIGHT_SURFACE_CONTAINER_HIGHEST),
)

/**
 * Custom Gruvbox-themed Material3 theme.
 * Dark by default; the accents are a cool slate blue-gray over neutral gray
 * surfaces, so only the error red carries a warm hue.
 */
@Composable
fun LocalAssistantTheme(
    darkTheme: Boolean = true, // Default to dark theme
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) GruvboxDarkColorScheme else GruvboxLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
