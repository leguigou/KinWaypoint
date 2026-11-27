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

/** Ligne des paramètres qui ouvre le check-up (« Tout est au vert » ou « 2 points à corriger »). */
@Composable
fun CheckupRow(items: List<CheckItem>, onClick: () -> Unit) {
    val problems = items.problems()
    val tint = if (problems == 0) AppTheme.colors.success else Warning
    SectionCard(container = tint.copy(alpha = 0.10f)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            IconBadge(if (problems == 0) Icons.Default.CheckCircle else Icons.Default.HealthAndSafety, tint, size = 44.dp)
            Column(Modifier.weight(1f)) {
                Text("Check-up du téléphone", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (problems == 0) "Tout est au vert" else if (problems == 1) "1 point à corriger" else "$problems points à corriger",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (problems == 0) AppTheme.colors.success else Warning,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Muted)
        }
    }
}

/** Fenêtre du check-up : chaque point avec son état et, s'il le faut, un bouton « Corriger ». */
@Composable
fun CheckupDialog(title: String, items: List<CheckItem>, onDismiss: () -> Unit) {
    val problems = items.problems()
    val tint = if (problems == 0) AppTheme.colors.success else Warning
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(64.dp).clip(CircleShape).background(tint.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                        Icon(if (problems == 0) Icons.Default.CheckCircle else Icons.Default.Warning, contentDescription = null, tint = tint, modifier = Modifier.size(36.dp))
                    }
                    Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    Text(
                        if (problems == 0) "Tout est au vert ✅" else if (problems == 1) "1 point à corriger" else "$problems points à corriger",
                        style = MaterialTheme.typography.bodyMedium,
                        color = tint,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items.forEach { CheckRow(it) }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Fermer") }
            }
        }
    }
}

@Composable
private fun CheckRow(item: CheckItem) {
    val tint = when {
        item.ok -> AppTheme.colors.success
        item.optional -> Muted
        else -> Warning
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(if (item.ok) Icons.Default.CheckCircle else item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(item.title + if (item.optional && !item.ok) " (facultatif)" else "", style = MaterialTheme.typography.titleSmall)
            Text(item.detail, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        if (!item.ok && item.fix != null) {
            FilledTonalButton(onClick = item.fix, shape = CircleShape, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) {
                Text(item.fixLabel ?: "Corriger", maxLines = 1)
            }
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
