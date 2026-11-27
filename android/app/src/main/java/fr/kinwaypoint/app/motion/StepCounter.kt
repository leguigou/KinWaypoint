package fr.kinwaypoint.app.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import java.time.LocalDate

/**
 * Podomètre : le capteur matériel TYPE_STEP_COUNTER (quasi gratuit en
 * batterie, les pas sont comptés par la puce même écran éteint) donne un total
 * depuis le démarrage du téléphone. On en déduit les pas du jour.
 *
 * Mémorisé dans les préférences : base du jour, dernier total lu, et pas déjà
 * comptés avant un éventuel redémarrage (le total repart alors de zéro).
 */
class StepCounter(context: Context, private val onSteps: (stepsToday: Int, total: Long) -> Unit) : SensorEventListener {

    private val prefs = context.getSharedPreferences("steps", Context.MODE_PRIVATE)
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    val available: Boolean get() = sensor != null

    @Volatile private var started = false

    /**
     * Pas du jour, ou null si le podomètre n'est pas actif. Relu dans les
     * préférences : le capteur ne donne rien tant qu'on ne marche pas, mais
     * après un redémarrage du suivi la valeur du jour reste connue (sinon les
     * positions partaient sans pas et la barre disparaissait chez les parents).
     * Nouveau jour sans encore aucun pas compté : 0.
     */
    val stepsToday: Int?
        get() {
            if (!started) return null
            val day = prefs.getString("day", null)
            if (day != LocalDate.now().toString()) return 0
            return DailySteps(day, prefs.getLong("base", 0), prefs.getLong("last", 0), prefs.getLong("carried", 0)).today
        }

    fun start(): Boolean {
        val s = sensor ?: return false
        // Regroupement des mesures jusqu'à 30 s : la puce ne réveille pas le téléphone à chaque pas.
        started = runCatching {
            sensorManager?.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL, 30_000_000) == true
        }.getOrDefault(false)
        return started
    }

    fun stop() {
        runCatching { sensorManager?.unregisterListener(this) }
        started = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val total = event.values.firstOrNull()?.toLong() ?: return
        val state = DailySteps(
            day = prefs.getString("day", null),
            base = prefs.getLong("base", 0),
            last = prefs.getLong("last", 0),
            carried = prefs.getLong("carried", 0),
        ).update(total, LocalDate.now().toString())
        prefs.edit()
            .putString("day", state.day)
            .putLong("base", state.base)
            .putLong("last", state.last)
            .putLong("carried", state.carried)
            .apply()
        onSteps(state.today, total)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

/** Calcul des pas du jour à partir du total « depuis le démarrage » (logique pure, testée). */
data class DailySteps(val day: String?, val base: Long, val last: Long, val carried: Long) {
    val today: Int get() = (carried + (last - base)).coerceAtLeast(0).toInt()

    fun update(total: Long, currentDay: String): DailySteps = when {
        // Nouveau jour (ou première mesure) : on repart de zéro.
        day != currentDay -> DailySteps(currentDay, base = total, last = total, carried = 0)
        // Le total a baissé : le téléphone a redémarré, on garde les pas déjà faits.
        total < last -> DailySteps(day, base = 0, last = total, carried = carried + (last - base))
        else -> copy(last = total)
    }
}
