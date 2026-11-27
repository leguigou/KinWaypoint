package fr.kinwaypoint.app.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SMS de secours au moment d'un SOS : envoyés par le téléphone de l'enfant, avec
 * le réseau téléphonique seul (pas besoin d'Internet), à chaque parent qui a
 * renseigné son numéro (Paramètres → Compte). Les numéros et le prénom sont
 * lus d'avance (attribut kwSosPhones de l'appareil, via RemoteActions) et
 * gardés sur le téléphone : ils servent même hors connexion.
 */
object SosSms {
    private const val TAG = "KinWaypoint"
    private const val PREFS = "sos_sms"

    /** Mémorise le prénom de ce téléphone et les numéros des parents (JSON {"compte": "numéro"}). */
    fun remember(context: Context, name: String, phonesJson: String) {
        val phones = runCatching {
            val json = JSONObject(phonesJson)
            json.keys().asSequence().map { json.optString(it).trim() }.filter { it.isNotBlank() }.distinct().toList()
        }.getOrDefault(emptyList())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("phones", phones.joinToString("\n"))
            .apply { if (name.isNotBlank()) putString("name", name) }
            .apply()
    }

    fun phones(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("phones", "").orEmpty()
            .lines().filter { it.isNotBlank() }

    /** Le téléphone sait envoyer des SMS (pas une tablette sans carte SIM). */
    fun hasTelephony(context: Context) =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)

    fun permitted(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    /**
     * Envoie le SMS de SOS à tous les parents. location = dernière position
     * connue (null : pas encore de position, un 2e SMS suivra avec followUp).
     */
    fun send(context: Context, location: Location?, battery: Int?, followUp: Boolean = false) {
        val numbers = phones(context)
        if (numbers.isEmpty() || !hasTelephony(context) || !permitted(context)) {
            Log.w(TAG, "SMS de SOS non envoyé : ${if (numbers.isEmpty()) "aucun numéro de parent" else "SMS impossible ou non autorisé"}")
            return
        }
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("name", null) ?: "ton enfant"
        val text = message(name, location, battery, followUp, System.currentTimeMillis())
        val sms = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(SmsManager::class.java)
            else @Suppress("DEPRECATION") SmsManager.getDefault()
        }.getOrNull() ?: return
        numbers.forEach { number ->
            runCatching {
                // Message long (lien + détails) : envoyé en plusieurs morceaux, recollés par le téléphone du parent.
                sms.sendMultipartTextMessage(number, null, sms.divideMessage(text), null, null)
            }.onSuccess { Log.i(TAG, "SMS de SOS envoyé") }
                .onFailure { Log.w(TAG, "SMS de SOS impossible : ${it.message}") }
        }
    }

    /** Texte du SMS (sans emoji : il reste court et passe sur tous les téléphones). */
    internal fun message(name: String, location: Location?, battery: Int?, followUp: Boolean, now: Long): String {
        val time = SimpleDateFormat("dd/MM 'à' HH:mm", Locale.FRANCE)
        val hour = SimpleDateFormat("HH:mm", Locale.FRANCE)
        if (location == null) {
            return "SOS de $name (${time.format(Date(now))}). Position pas encore connue : un 2e SMS suivra." +
                (battery?.let { " Batterie $it %." } ?: "")
        }
        val link = "https://maps.google.com/?q=" +
            String.format(Locale.US, "%.5f,%.5f", location.latitude, location.longitude)
        val ageMin = ((now - location.time) / 60_000L).coerceAtLeast(0)
        val details = buildList {
            if (location.hasAccuracy()) add("precision ${location.accuracy.toInt()} m")
            if (ageMin >= 2) add("mesure de ${hour.format(Date(location.time))}")
            battery?.let { add("batterie $it %") }
        }.joinToString(", ")
        return if (followUp) {
            "SOS de $name : position actualisée (${hour.format(Date(location.time))})\n$link" +
                if (details.isNotEmpty()) "\n($details)" else ""
        } else {
            "SOS de $name (${time.format(Date(now))})\nPosition : $link" +
                if (details.isNotEmpty()) "\n($details)" else ""
        }
    }
}
