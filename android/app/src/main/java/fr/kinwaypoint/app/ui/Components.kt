package fr.kinwaypoint.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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

/** Logo de l'appli : le « K » route + épingle (même dessin que l'icône). */
@Composable
fun BrandMark(size: Dp = 64.dp) {
    Image(
        painter = androidx.compose.ui.res.painterResource(fr.kinwaypoint.app.R.drawable.ic_logo),
        contentDescription = null,
        modifier = Modifier
            .size(size)
            .shadow(elevation = 12.dp, shape = RoundedCornerShape(size * 0.28f), ambientColor = AppTheme.colors.heroGradient.first()),
    )
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
fun StatusPill(text: String, color: Color, icon: ImageVector? = null, showDot: Boolean = true) {
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
        } else if (showDot) {
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

/** Couleurs proposées pour les profils (tracés, avatars). */
val ProfilePalette = listOf(
    "#5B5BD6", "#8B5CF6", "#D946EF", "#E5484D", "#FF7A59",
    "#F59F00", "#12B886", "#15AABF", "#3B82F6", "#475569",
)

fun parseHex(hex: String?): Color? =
    hex?.takeIf { it.matches(Regex("#[0-9A-Fa-f]{6}")) }?.let { Color(android.graphics.Color.parseColor(it)) }

/** Couleur d'un profil : celle choisie par les parents, sinon celle déduite du prénom. */
fun profileColor(name: String, hex: String?): Color = parseHex(hex) ?: avatarColors(name).first()

/** Dégradé d'avatar : à partir de la couleur choisie si elle existe. */
fun profileGradient(name: String, hex: String?): List<Color> =
    parseHex(hex)?.let { listOf(it, androidx.compose.ui.graphics.lerp(it, Color.White, 0.3f)) } ?: avatarColors(name)

/** Avatar rond : la photo si elle existe, sinon l'initiale sur un dégradé. */
@Composable
fun Avatar(name: String, size: Dp = 48.dp, photo: Bitmap? = null, colorHex: String? = null) {
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(Brush.linearGradient(profileGradient(name, colorHex))),
        contentAlignment = Alignment.Center,
    ) {
        if (photo != null) {
            Image(
                bitmap = photo.asImageBitmap(),
                contentDescription = "Photo de $name",
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            Text(
                name.trim().firstOrNull()?.uppercase() ?: "?",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.42f).sp,
            )
        }
    }
}

/**
 * Pastille bouclier des comptes parents, en haut à droite de l'avatar (comme la
 * pastille de connexion en bas à droite) : distingue les parents des enfants.
 */
@Composable
fun ParentShield(modifier: Modifier = Modifier, size: Dp = 20.dp) {
    Box(
        modifier.size(size).clip(CircleShape).background(Color.White)
            .border(1.dp, Color(0x335B5BD6), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = androidx.compose.ui.res.painterResource(fr.kinwaypoint.app.R.drawable.ic_parent_shield),
            contentDescription = "Parent",
            modifier = Modifier.size(size * 0.74f),
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


/**
 * Anneau de progression vers l'objectif de pas : se remplit dans la couleur
 * donnée, devient vert et affiche 🎉 quand l'objectif est atteint.
 */
@Composable
fun StepGoalRing(steps: Int, goal: Int, color: Color, size: Dp = 120.dp, stroke: Dp = 12.dp) {
    val progress = if (goal > 0) (steps.toFloat() / goal).coerceIn(0f, 1f) else 0f
    val reached = steps >= goal
    val fill = if (reached) AppTheme.colors.success else color
    val track = color.copy(alpha = 0.15f)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(size)) {
            val w = stroke.toPx()
            val arc = androidx.compose.ui.geometry.Size(this.size.width - w, this.size.height - w)
            val topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2)
            drawArc(track, -90f, 360f, false, topLeft, arc, style = androidx.compose.ui.graphics.drawscope.Stroke(w))
            drawArc(
                fill, -90f, 360f * progress, false, topLeft, arc,
                style = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (reached) "🎉" else "👟", fontSize = (size.value * 0.16f).sp)
            Text(formatSteps(steps), fontWeight = FontWeight.Bold, fontSize = (size.value * 0.15f).sp)
            Text("/ ${formatSteps(goal)}", fontSize = (size.value * 0.09f).sp, color = Muted)
        }
    }
}

/** Barre fine de progression vers l'objectif de pas (fiches, listes). */
@Composable
fun StepGoalBar(steps: Int, goal: Int, color: Color, modifier: Modifier = Modifier) {
    val progress = if (goal > 0) (steps.toFloat() / goal).coerceIn(0f, 1f) else 0f
    val reached = steps >= goal
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (reached) "🎉 " else "👟 ") + "${formatSteps(steps)} / ${formatSteps(goal)} pas",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            Text("${(progress * 100).toInt()} %", style = MaterialTheme.typography.labelMedium, color = Muted)
        }
        androidx.compose.material3.LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
            color = if (reached) AppTheme.colors.success else color,
            trackColor = color.copy(alpha = 0.15f),
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}

/**
 * Curseur de l'appli : poignée ronde contourée de blanc avec une ombre légère
 * (au lieu de la barre verticale Material), piste fine et arrondie.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun KwSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val accent = if (enabled) MaterialTheme.colorScheme.primary else Muted
    androidx.compose.material3.Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        thumb = {
            Box(
                Modifier
                    .size(26.dp)
                    .shadow(4.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color.White)
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(accent),
            )
        },
        track = { state ->
            val span = state.valueRange.endInclusive - state.valueRange.start
            val fraction = if (span > 0f) ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f) else 0f
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.16f)),
            ) {
                Box(Modifier.fillMaxWidth(fraction).height(8.dp).clip(CircleShape).background(accent))
            }
        },
    )
}
