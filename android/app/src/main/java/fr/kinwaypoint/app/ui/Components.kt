package fr.kinwaypoint.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Logo de l'appli : épingle blanche sur un carré arrondi en dégradé. */
@Composable
fun BrandMark(size: Dp = 64.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .shadow(elevation = 12.dp, shape = RoundedCornerShape(size * 0.3f), ambientColor = AppTheme.colors.heroGradient.first())
            .clip(RoundedCornerShape(size * 0.3f))
            .background(Brush.linearGradient(AppTheme.colors.heroGradient)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.LocationOn, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.55f))
    }
}

/** Carte de contenu standard : fond plein, coins arrondis, ombre très légère. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    container: Color = CardSurface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

/** Petite pastille colorée : « En ligne », « 73 % », etc. */
@Composable
fun StatusPill(text: String, color: Color, icon: ImageVector? = null) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        } else {
            Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        }
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

private val AvatarPalette = listOf(
    listOf(Color(0xFF5B5BD6), Color(0xFF8B5CF6)),
    listOf(Color(0xFFFF7A59), Color(0xFFFF4D8D)),
    listOf(Color(0xFF12B886), Color(0xFF15AABF)),
    listOf(Color(0xFFF59F00), Color(0xFFFF7A59)),
    listOf(Color(0xFF3B82F6), Color(0xFF06B6D4)),
    listOf(Color(0xFFD946EF), Color(0xFF8B5CF6)),
)

/** Couleurs stables pour un prénom donné (même prénom → même couleur partout). */
fun avatarColors(name: String): List<Color> =
    AvatarPalette[(name.lowercase().hashCode() and Int.MAX_VALUE) % AvatarPalette.size]

/** Avatar rond en dégradé avec l'initiale du prénom. */
@Composable
fun Avatar(name: String, size: Dp = 48.dp) {
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(Brush.linearGradient(avatarColors(name))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().firstOrNull()?.uppercase() ?: "?",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.42f).sp,
        )
    }
}

/** Icône dans une pastille ronde teintée, pour les en-têtes de cartes. */
@Composable
fun IconBadge(icon: ImageVector, tint: Color, size: Dp = 44.dp) {
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}
