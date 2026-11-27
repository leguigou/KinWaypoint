package fr.kinwaypoint.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.kinwaypoint.app.net.TracePoint

/** Résumé de la batterie sur une journée. */
data class BatteryStats(
    val min: Int,
    val max: Int,
    val last: Int,
    /** Décharge moyenne (% par heure) hors charge, ou null si trop peu de mesures. */
    val dischargePerHour: Double?,
    /** Nombre de mises en charge. */
    val chargeSessions: Int,
    /** Temps passé en charge (minutes). */
    val chargingMinutes: Long,
)

/** Au-delà de 2 h sans mesure, on ne suppose rien sur ce qui s'est passé entre les deux. */
private const val MAX_GAP_MS = 2 * 3600 * 1000L

/** Calcule le résumé à partir des positions du jour (null si aucune mesure de batterie). */
fun batteryStats(points: List<TracePoint>): BatteryStats? {
    val measured = points.filter { it.battery != null }.sortedBy { it.time }
    if (measured.isEmpty()) return null
    var drop = 0.0
    var dischargeMs = 0L
    var chargingMs = 0L
    var sessions = 0
    var wasCharging = false
    measured.forEachIndexed { i, p ->
        val charging = p.charging == true
        if (charging && !wasCharging) sessions++
        wasCharging = charging
        val next = measured.getOrNull(i + 1) ?: return@forEachIndexed
        val dt = next.time - p.time
        if (dt <= 0 || dt > MAX_GAP_MS) return@forEachIndexed
        if (charging) {
            chargingMs += dt
        } else if (next.charging != true) {
            // Intervalle sans charge : la baisse mesure la consommation.
            drop += maxOf(0, p.battery!! - next.battery!!)
            dischargeMs += dt
        }
    }
    val levels = measured.map { it.battery!! }
    return BatteryStats(
        min = levels.min(),
        max = levels.max(),
        last = levels.last(),
        dischargePerHour = if (dischargeMs >= 30 * 60_000L) drop / (dischargeMs / 3_600_000.0) else null,
        chargeSessions = sessions,
        chargingMinutes = chargingMs / 60_000L,
    )
}

/**
 * Courbe de charge et de décharge sur la journée (0 h → 24 h), 0 à 100 %.
 * Les périodes en charge sont tracées en vert ; repères à 20 % (batterie faible).
 */
@Composable
fun BatteryCurveCard(points: List<TracePoint>, dayStart: Long, color: Color) {
    val stats = batteryStats(points) ?: return
    val measured = points.filter { it.battery != null }.sortedBy { it.time }
    val dayMs = 24 * 3600 * 1000L
    val green = AppTheme.colors.success
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val low = Danger.copy(alpha = 0.35f)

    SectionCard(modifier = Modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                if (measured.last().charging == true) Icons.Default.BatteryChargingFull else Icons.Default.BatteryFull,
                contentDescription = null,
                tint = if (stats.last < 20) Danger else color,
            )
            Text("Batterie de la journée", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("${stats.last} %", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }

        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val w = size.width
            val h = size.height
            fun x(t: Long) = ((t - dayStart).toFloat() / dayMs).coerceIn(0f, 1f) * w
            fun y(level: Int) = h - level.coerceIn(0, 100) / 100f * h
            // Repères 25 / 50 / 75 % et seuil 20 % (batterie faible).
            listOf(25, 50, 75).forEach { drawLine(grid, Offset(0f, y(it)), Offset(w, y(it)), 1.dp.toPx()) }
            drawLine(low, Offset(0f, y(20)), Offset(w, y(20)), 1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            // Repères 6 h / 12 h / 18 h.
            listOf(6, 12, 18).forEach { hour ->
                val gx = w * hour / 24f
                drawLine(grid, Offset(gx, 0f), Offset(gx, h), 1.dp.toPx())
            }
            // Aire sous la courbe, puis la courbe par morceaux (vert pendant la charge).
            val area = Path()
            measured.forEachIndexed { i, p ->
                if (i == 0) { area.moveTo(x(p.time), h); area.lineTo(x(p.time), y(p.battery!!)) } else area.lineTo(x(p.time), y(p.battery!!))
            }
            area.lineTo(x(measured.last().time), h)
            area.close()
            drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.25f), color.copy(alpha = 0.02f))))
            for (i in 0 until measured.size - 1) {
                val a = measured[i]
                val b = measured[i + 1]
                // Trou de plus de 2 h : on ne relie pas (téléphone éteint, hors ligne…).
                if (b.time - a.time > MAX_GAP_MS) continue
                drawLine(
                    if (a.charging == true) green else color,
                    Offset(x(a.time), y(a.battery!!)),
                    Offset(x(b.time), y(b.battery!!)),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            measured.lastOrNull()?.let { p ->
                drawCircle(Color.White, 6.dp.toPx(), Offset(x(p.time), y(p.battery!!)))
                drawCircle(if (p.charging == true) green else color, 4.dp.toPx(), Offset(x(p.time), y(p.battery!!)))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("0 h", "6 h", "12 h", "18 h", "24 h").forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = Muted)
            }
        }

        // Chiffres clés.
        Row(Modifier.fillMaxWidth()) {
            BatteryFigure("${stats.min} → ${stats.max} %", "Min → max", Modifier.weight(1f))
            BatteryFigure(
                stats.dischargePerHour?.let { "≈ ${String.format(java.util.Locale.FRANCE, "%.1f", it)} %/h" } ?: "—",
                "Décharge",
                Modifier.weight(1f),
            )
            BatteryFigure(
                if (stats.chargeSessions == 0) "Aucune" else "${stats.chargeSessions} · ${durationText(stats.chargingMinutes)}",
                "Recharges",
                Modifier.weight(1f),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Canvas(Modifier.size(10.dp)) { drawCircle(green) }
            Text("en charge", style = MaterialTheme.typography.labelSmall, color = Muted)
            Canvas(Modifier.size(10.dp)) { drawCircle(color) }
            Text("sur batterie", style = MaterialTheme.typography.labelSmall, color = Muted)
        }
    }
}

@Composable
private fun BatteryFigure(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Muted)
    }
}

private fun durationText(minutes: Long) = when {
    minutes < 60 -> "$minutes min"
    else -> "${minutes / 60} h ${String.format("%02d", minutes % 60)}"
}
