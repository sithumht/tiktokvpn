package com.tiktokvpn.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val Cyan = Color(0xFF25F4EE)
val Pink = Color(0xFFFE2C55)
val Ink = Color(0xFF000000)
val Panel = Color(0xFF101013)
val PanelRaised = Color(0xFF17171B)
val MutedText = Color(0xFF9A9AA2)

/** One scheme, dark by design: the interface never leaves the black canvas. */
private val TikTokColors = darkColorScheme(
    primary = Cyan,
    onPrimary = Ink,
    primaryContainer = PanelRaised,
    onPrimaryContainer = Cyan,
    secondary = Pink,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF2A0D15),
    onSecondaryContainer = Pink,
    tertiary = Color.White,
    background = Ink,
    onBackground = Color(0xFFF2F2F4),
    surface = Ink,
    onSurface = Color(0xFFF2F2F4),
    surfaceVariant = Panel,
    onSurfaceVariant = Color(0xFFB6B6BD),
    outline = Color(0xFF2B2B31),
    outlineVariant = Color(0xFF1C1C21),
    error = Pink,
    onError = Color.White
)

private val TikTokShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun TikTokVpnTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TikTokColors,
        shapes = TikTokShapes,
        content = content
    )
}
