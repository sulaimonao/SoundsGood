package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val SoundsGoodDarkColorScheme = darkColorScheme(
    primary = CoralAmber,
    onPrimary = OnCoralAmber,
    primaryContainer = CoralContainer,
    onPrimaryContainer = OnCoralContainer,
    secondary = StudioTeal,
    onSecondary = OnStudioTeal,
    secondaryContainer = StudioTealContainer,
    onSecondaryContainer = OnStudioTealContainer,
    tertiary = VinylGold,
    background = ObsidianBg,
    onBackground = InkLight,
    surface = ObsidianSurface,
    onSurface = InkLight,
    surfaceVariant = ObsidianSurfaceVariant,
    onSurfaceVariant = InkMuted,
    surfaceContainerLow = ObsidianSurface,
    surfaceContainer = ObsidianSurfaceVariant,
    surfaceContainerHigh = ObsidianSurfaceElevated,
    outline = OutlineDark,
    error = StudioError,
    errorContainer = StudioErrorContainer
)

private val SoundsGoodLightColorScheme = lightColorScheme(
    primary = TerracottaPrimary,
    onPrimary = OnTerracottaPrimary,
    primaryContainer = TerracottaContainer,
    onPrimaryContainer = OnTerracottaContainer,
    secondary = DeepTealSecondary,
    onSecondary = OnDeepTeal,
    secondaryContainer = DeepTealContainer,
    onSecondaryContainer = OnDeepTealContainer,
    tertiary = VinylGold,
    background = AlabasterBg,
    onBackground = InkDark,
    surface = AlabasterSurface,
    onSurface = InkDark,
    surfaceVariant = AlabasterSurfaceVariant,
    onSurfaceVariant = InkDarkMuted,
    surfaceContainerLow = AlabasterSurface,
    surfaceContainer = AlabasterSurfaceVariant,
    surfaceContainerHigh = AlabasterSurfaceElevated,
    outline = OutlineLight
)

val SoundsGoodShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun SoundsGoodTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> SoundsGoodDarkColorScheme
        else -> SoundsGoodLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = SoundsGoodTypography,
        shapes = SoundsGoodShapes,
        content = content
    )
}
