package fr.kinwaypoint.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Fond de l'accueil : léger dégradé lavande en haut et deux halos discrets,
 * derrière les boutons et la carte principale. S'adapte au thème sombre
 * (couleurs du thème, pas de blanc forcé).
 */
@Composable
fun HomeBackdrop(content: @Composable BoxScope.() -> Unit) {
    val glow = MaterialTheme.colorScheme.primary
    val soft = AppTheme.colors.softPrimary
    val base = MaterialTheme.colorScheme.background
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(0f to soft, 0.35f to base, 1f to base)),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // Halo en haut à droite, derrière les boutons ronds.
            drawCircle(
                Brush.radialGradient(listOf(glow.copy(alpha = 0.10f), Color.Transparent), center = Offset(size.width * 0.9f, 0f), radius = size.width * 0.7f),
                radius = size.width * 0.7f,
                center = Offset(size.width * 0.9f, 0f),
            )
            // Halo plus petit à gauche, derrière le titre.
            drawCircle(
                Brush.radialGradient(listOf(glow.copy(alpha = 0.06f), Color.Transparent), center = Offset(0f, size.height * 0.08f), radius = size.width * 0.45f),
                radius = size.width * 0.45f,
                center = Offset(0f, size.height * 0.08f),
            )
        }
        content()
    }
}

/** Bouton rond de l'en-tête : pastille claire avec ombre douce, pictogramme dans la couleur de l'appli. */
@Composable
fun RoundIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(48.dp)
            .shadow(6.dp, CircleShape, ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
            .clip(CircleShape)
            .background(AppTheme.colors.card)
            .clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
    }
}

/** Trait de pinceau sous « Ma famille » (violet → rose). */
@Composable
fun TitleUnderline(modifier: Modifier = Modifier) {
    val brush = Brush.horizontalGradient(listOf(Color(0xFF7C4DE0), Color(0xFFD946EF).copy(alpha = 0.7f)))
    Canvas(modifier.width(64.dp).height(10.dp)) {
        val path = Path().apply {
            moveTo(2.dp.toPx(), size.height * 0.75f)
            quadraticTo(size.width * 0.5f, size.height * 0.15f, size.width - 2.dp.toPx(), size.height * 0.45f)
        }
        drawPath(path, brush, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
    }
}

/**
 * Carte principale « Voir la carte » : dégradé de l'appli, petit plan stylisé
 * à droite (rues, parcs, épingle avec ondes), état de la famille en bas.
 */
@Composable
fun MapHeroCard(status: String, online: Int, total: Int, onClick: () -> Unit, subtitle: String? = null) {
    val gradient = AppTheme.colors.heroGradient
    Box(
        Modifier.fillMaxWidth().height(150.dp)
            .shadow(14.dp, RoundedCornerShape(28.dp), ambientColor = gradient.first(), spotColor = gradient.first())
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(gradient))
            .clickable(onClickLabel = "Voir la carte", onClick = onClick),
    ) {
        // Plan stylisé, à droite.
        Canvas(Modifier.fillMaxHeight().fillMaxWidth(0.5f).align(Alignment.CenterEnd)) {
            val w = size.width
            val h = size.height
            val street = Color.White.copy(alpha = 0.16f)
            val park = Color(0xFF34D399).copy(alpha = 0.28f)
            drawRoundRect(park, Offset(w * 0.55f, -h * 0.05f), Size(w * 0.5f, h * 0.35f), CornerRadius(18.dp.toPx()))
            drawRoundRect(park, Offset(w * 0.72f, h * 0.72f), Size(w * 0.4f, h * 0.4f), CornerRadius(18.dp.toPx()))
            drawRoundRect(park, Offset(w * 0.08f, h * 0.05f), Size(w * 0.22f, h * 0.2f), CornerRadius(12.dp.toPx()))
            listOf(
                Offset(0f, h * 0.35f) to Offset(w, h * 0.15f),
                Offset(0f, h * 0.8f) to Offset(w, h * 0.55f),
                Offset(w * 0.3f, 0f) to Offset(w * 0.45f, h),
                Offset(w * 0.75f, 0f) to Offset(w * 0.6f, h),
            ).forEach { (a, b) -> drawLine(street, a, b, strokeWidth = 7.dp.toPx(), cap = StrokeCap.Round) }
            // Ondes autour de l'épingle.
            val pin = Offset(w * 0.6f, h * 0.62f)
            listOf(34.dp, 24.dp).forEachIndexed { i, r ->
                drawOval(
                    Color.White.copy(alpha = if (i == 0) 0.22f else 0.35f),
                    topLeft = Offset(pin.x - r.toPx(), pin.y - r.toPx() * 0.42f),
                    size = Size(r.toPx() * 2, r.toPx() * 0.84f),
                    style = Stroke(2.dp.toPx()),
                )
            }
        }
        // Épingle.
        Box(
            Modifier.align(Alignment.CenterEnd).padding(end = 40.dp, bottom = 20.dp).size(54.dp)
                .shadow(8.dp, CircleShape)
                .clip(CircleShape)
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.LocationOn, contentDescription = null, tint = gradient.last(), modifier = Modifier.size(34.dp))
        }

        Column(
            Modifier.fillMaxWidth(0.68f).fillMaxHeight().padding(start = 22.dp, top = 22.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Map, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                Text("Voir la carte", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
                Box(
                    Modifier.size(30.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp)) }
            }
            // Deux lignes : état (« ● 3 sur 4 en ligne »), puis « Actualisé à … ».
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (total > 0) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(if (online > 0) Color(0xFF4ADE80) else Color.White.copy(alpha = 0.5f)))
                    }
                    Text(status, color = Color.White.copy(alpha = 0.95f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                subtitle?.let {
                    Text(it, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
        }
    }
}

/** Carte « Bilan de la semaine » : pictogramme en carré arrondi pêche, vague décorative en bas à droite. */
@Composable
fun WeeklyCard(onClick: () -> Unit) {
    val peach = Color(0xFFFF9F6B)
    Box(
        Modifier.fillMaxWidth()
            .shadow(3.dp, RoundedCornerShape(28.dp))
            .clip(RoundedCornerShape(28.dp))
            .background(AppTheme.colors.card)
            .clickable(onClickLabel = "Bilan de la semaine", onClick = onClick),
    ) {
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            val wave = Path().apply {
                moveTo(w * 0.45f, h)
                cubicTo(w * 0.6f, h * 0.7f, w * 0.75f, h * 0.95f, w, h * 0.55f)
                lineTo(w, h)
                close()
            }
            drawPath(wave, Brush.horizontalGradient(listOf(peach.copy(alpha = 0.0f), peach.copy(alpha = 0.16f))))
        }
        Row(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier.size(58.dp).clip(RoundedCornerShape(18.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFFFFE7DA), Color(0xFFFFD3BE)))),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.BarChart, contentDescription = null, tint = Color(0xFFF2703A), modifier = Modifier.size(30.dp)) }
            Column(Modifier.weight(1f)) {
                Text("Bilan de la semaine", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Pas, objectif, distance, temps passé à l'école…", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface) }
        }
    }
}

/** En-tête « Membres » avec le nombre de membres dans une gélule. */
@Composable
fun MembersHeader(count: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Membres", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Row(
            Modifier.clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Default.People, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Text(
                if (count == 1) "1 membre" else "$count membres",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
