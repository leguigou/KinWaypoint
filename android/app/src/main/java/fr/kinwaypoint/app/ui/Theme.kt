package fr.kinwaypoint.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Green = Color(0xFF1F6F5C)
val GreenLight = Color(0xFF2E8F76)
val Muted = Color(0xFF5B6B66)
val Danger = Color(0xFFC62828)
val Warning = Color(0xFFEF6C00)
val Ok = Color(0xFF2E7D32)
val Idle = Color(0xFF9E9E9E)
val SoftGreen = Color(0xFFEFF5F3)
val SoftOrange = Color(0xFFFFF4E5)
val SoftRed = Color(0xFFFDECEA)

@Composable
fun KinWaypointTheme(content: @Composable () -> Unit) {
    val colors = lightColorScheme(
        primary = Green,
        onPrimary = Color.White,
        secondary = GreenLight,
        background = Color(0xFFF7F9F8),
        surface = Color.White,
        error = Danger,
    )
    MaterialTheme(colorScheme = colors, content = content)
}
