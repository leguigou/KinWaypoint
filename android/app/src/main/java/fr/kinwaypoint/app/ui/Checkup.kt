package fr.kinwaypoint.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.clickable

/** Un point du check-up : bon (vert), à corriger (orange) ou facultatif (gris). */
data class CheckItem(
    val icon: ImageVector,
    val title: String,
    val detail: String,
    val ok: Boolean,
    val optional: Boolean = false,
    val fixLabel: String? = null,
    val fix: (() -> Unit)? = null,
)

/** Nombre de points indispensables à corriger. */
fun List<CheckItem>.problems() = count { !it.ok && !it.optional }

/** Couleurs du dégradé : vert si tout va bien, orange s'il reste des points à corriger. */
private fun checkupGradient(problems: Int) =
    if (problems == 0) listOf(Color(0xFF12B886), Color(0xFF15AABF)) else listOf(Color(0xFFF59F00), Color(0xFFFF7A59))

private fun checkupHeadline(problems: Int) = when (problems) {
    0 -> "Tout est au vert"
    1 -> "1 point à corriger"
    else -> "$problems points à corriger"
}

/** Anneau de score : part des vérifications indispensables réussies, « 7/9 » au centre. */
@Composable
private fun ScoreRing(ok: Int, total: Int, size: androidx.compose.ui.unit.Dp, stroke: androidx.compose.ui.unit.Dp) {
    val progress = if (total == 0) 1f else ok / total.toFloat()
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(size)) {
            val w = stroke.toPx()
            val arc = androidx.compose.ui.geometry.Size(this.size.width - w, this.size.height - w)
            val topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2)
            drawArc(Color.White.copy(alpha = 0.25f), -90f, 360f, false, topLeft, arc, style = androidx.compose.ui.graphics.drawscope.Stroke(w))
            drawArc(
                Color.White, -90f, 360f * progress, false, topLeft, arc,
                style = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        }
        if (ok == total) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.42f))
        } else {
            Text("$ok/$total", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        }
    }
}

/**
 * Carte « Check-up du téléphone » en tête des réglages : dégradé vert (tout va
 * bien) ou orange (à corriger), anneau de score, touchable en entier.
 */
@Composable
fun CheckupRow(items: List<CheckItem>, onClick: () -> Unit) {
    val required = items.filter { !it.optional }
    val okCount = required.count { it.ok }
    val problems = required.size - okCount
    androidx.compose.material3.Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .background(androidx.compose.ui.graphics.Brush.linearGradient(checkupGradient(problems)))
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ScoreRing(okCount, required.size, size = 56.dp, stroke = 5.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Check-up du téléphone", style = MaterialTheme.typography.titleMedium, color = Color.White)
                Text(
                    checkupHeadline(problems),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.9f),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Color.White) }
        }
    }
}

/**
 * Fenêtre du check-up : bandeau dégradé avec le score, puis « À corriger »
 * (avec bouton), « Facultatif », et « Tout va bien » en lignes compactes.
 */
@Composable
fun CheckupDialog(title: String, items: List<CheckItem>, onDismiss: () -> Unit) {
    val required = items.filter { !it.optional }
    val okCount = required.count { it.ok }
    val problems = required.size - okCount
    val toFix = items.filter { !it.ok && !it.optional }
    val optional = items.filter { !it.ok && it.optional }
    val fine = items.filter { it.ok }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
            Column {
                // Bandeau : score et verdict.
                Row(
                    Modifier.fillMaxWidth()
                        .background(androidx.compose.ui.graphics.Brush.linearGradient(checkupGradient(problems)))
                        .padding(horizontal = 22.dp, vertical = 22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    ScoreRing(okCount, required.size, size = 60.dp, stroke = 5.dp)
                    // Chaque texte tient sur une ligne (plus petit plutôt que coupé en deux).
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            title, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.9f),
                            maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Text(
                            checkupHeadline(problems), style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold,
                            maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Text(
                            "$okCount/${required.size} vérifications réussies",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.85f),
                            maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
                Column(
                    Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (toFix.isNotEmpty()) {
                        SectionLabel("À corriger")
                        toFix.forEach { FixCard(it, Warning) }
                    }
                    if (optional.isNotEmpty()) {
                        SectionLabel("Facultatif")
                        optional.forEach { FixCard(it, Muted) }
                    }
                    if (fine.isNotEmpty()) {
                        SectionLabel("Tout va bien")
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                                .background(AppTheme.colors.success.copy(alpha = 0.07f))
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            fine.forEach { item ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AppTheme.colors.success, modifier = Modifier.size(18.dp))
                                    Text(item.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(end = 12.dp, bottom = 8.dp)) { Text("Fermer") }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = Muted,
        letterSpacing = androidx.compose.ui.unit.TextUnit(1f, androidx.compose.ui.unit.TextUnitType.Sp),
        modifier = Modifier.padding(top = 6.dp, start = 4.dp),
    )
}

/** Point à corriger (ou facultatif) : pastille, titre, explication, bouton pleine largeur. */
@Composable
private fun FixCard(item: CheckItem, tint: Color) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(tint.copy(alpha = 0.09f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall)
                Text(item.detail, style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
        if (item.fix != null) {
            FilledTonalButton(
                onClick = item.fix,
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(containerColor = tint.copy(alpha = 0.18f), contentColor = if (tint == Muted) MaterialTheme.colorScheme.onSurface else tint),
            ) { Text(item.fixLabel ?: "Corriger", fontWeight = FontWeight.SemiBold, maxLines = 1) }
        }
    }
}

/** Points d'autorisation communs (enfant, et parent qui partage sa position). */
fun permissionChecks(p: PermissionSnapshot, a: PlatformActions, withListen: Boolean): List<CheckItem> =
    permissionSteps(p, a)
        .filter { withListen || !it.title.startsWith("Micro") && !it.title.startsWith("Affichage") }
        .map { s ->
            CheckItem(
                icon = s.icon,
                title = s.title.removeSuffix(" (facultatif)"),
                detail = s.detail,
                ok = s.done,
                optional = !s.required,
                fixLabel = "Corriger",
                fix = s.action,
            )
        }
