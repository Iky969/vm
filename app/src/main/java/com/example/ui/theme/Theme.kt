package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val KernoDarkColorScheme = darkColorScheme(
    primary = TerminalSlatePrimary,
    onPrimary = TerminalOnPrimary,
    primaryContainer = TerminalSlateContainer,
    onPrimaryContainer = TerminalOnSlateContainer,
    secondary = TerminalSecondary,
    onSecondary = TerminalOnPrimary,
    secondaryContainer = TerminalSecondaryContainer,
    onSecondaryContainer = TerminalOnSecondaryContainer,
    tertiary = StatusRunning,
    onTertiary = TerminalOnPrimary,
    background = TerminalCanvasBg,
    onBackground = TerminalTextPrimary,
    surface = TerminalSurface,
    onSurface = TerminalTextPrimary,
    surfaceVariant = TerminalSurfaceElevated,
    onSurfaceVariant = TerminalTextSecondary,
    surfaceContainerLowest = TerminalCanvasBg,
    surfaceContainerLow = TerminalSurface,
    surfaceContainer = TerminalSurfaceElevated,
    surfaceContainerHigh = TerminalSurfaceHighest,
    surfaceContainerHighest = TerminalSurfaceHighest,
    outline = TerminalBorder,
    outlineVariant = TerminalBorderSubtle,
    error = StatusError,
    onError = TerminalOnPrimary,
    errorContainer = StatusErrorBg,
    onErrorContainer = StatusError
)

private val KernoShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(12.dp)
)

@Composable
fun KernoVmTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = KernoDarkColorScheme,
        typography = KernoTypography,
        shapes = KernoShapes,
        content = content
    )
}
