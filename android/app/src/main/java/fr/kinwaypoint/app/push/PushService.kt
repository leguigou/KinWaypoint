package fr.kinwaypoint.app.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import fr.kinwaypoint.app.MainActivity
import fr.kinwaypoint.app.R
import fr.kinwaypoint.app.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Réception des alertes poussées par Traccar via Firebase.
 * Le canal `alerts` est distinct de la notification de suivi : une alerte de
 * zone ou un SOS doit sonner, pas passer en silence.
 */
class PushService : FirebaseMessagingService() {

    companion object {
        private const val CHANNEL_ID = "alerts"
    }

    override fun onNewToken(token: String) {
        CoroutineScope(Dispatchers.IO).launch {
            SettingsRepository(applicationContext).savePushToken(token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // Commande Traccar (pas une alerte à afficher) : un parent demande une
        // position tout de suite, et éventuellement de faire sonner ce téléphone.
        if (message.data["command"] != null) {
            fr.kinwaypoint.app.service.TrackingService.ping(applicationContext)
            // Réveil Firebase : on vient lire les demandes (sonner, écouter) posées sur l'appareil.
            runCatching {
                kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withTimeoutOrNull(8_000) { fr.kinwaypoint.app.service.RemoteActions.check(applicationContext, force = true) }
                }
            }
            return
        }
        val title = message.notification?.title
            ?: message.data["title"]
            ?: getString(R.string.app_name)
        val body = message.notification?.body
            ?: message.data["body"]
            ?: message.data["message"]
            ?: ""
        show(title, body)
    }

    private fun show(title: String, body: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Alertes famille",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "Entrée et sortie de zone, SOS" },
            )
        }

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        manager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
