package fr.kinwaypoint.app

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.kinwaypoint.app.service.ListenService
import fr.kinwaypoint.app.ui.AppTheme
import fr.kinwaypoint.app.ui.KinWaypointTheme

/**
 * Écran affiché sur le téléphone de l'enfant quand un parent l'écoute : il sait
 * toujours que le micro est partagé. Ouvrir cet écran (visible) est aussi ce qui
 * permet à Android d'autoriser le micro.
 */
class ListenActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Visible même téléphone verrouillé, écran rallumé.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        startFrom(intent)
        setContent {
            KinWaypointTheme {
                val active by ListenService.active.collectAsState()
                var started by remember { mutableStateOf(false) }
                LaunchedEffect(active) {
                    if (active) started = true
                    // Écoute terminée (le parent a raccroché) : l'écran se ferme tout seul.
                    if (!active && started) finish()
                }
                // Micro jamais connecté (serveur injoignable…) : on n'affiche pas l'écran indéfiniment.
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(30_000)
                    if (!ListenService.active.value) finish()
                }
                Box(
                    Modifier.fillMaxSize().background(Brush.verticalGradient(AppTheme.colors.heroGradient)).systemBarsPadding().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Box(
                            Modifier.size(120.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Default.Hearing, contentDescription = null, tint = Color.White, modifier = Modifier.size(64.dp)) }
                        Text(
                            if (active) "Un parent t'écoute en direct" else "Connexion du micro…",
                            color = Color.White,
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            "Ton micro est partagé avec ta famille pour ta sécurité. Rien n'est enregistré. " +
                                "L'écoute s'arrête toute seule quand ton parent raccroche (10 minutes au plus).",
                            color = Color.White.copy(alpha = 0.9f),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                        Button(
                            onClick = { moveTaskToBack(true) },
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) { Text("D'accord") }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        startFrom(intent)
    }

    /** L'écran est visible : Android autorise maintenant le micro. */
    private fun startFrom(intent: android.content.Intent?) {
        val room = intent?.getStringExtra(ListenService.EXTRA_ROOM) ?: return
        val secret = intent.getStringExtra(ListenService.EXTRA_SECRET) ?: return
        ListenService.start(this, room, secret)
    }
}
