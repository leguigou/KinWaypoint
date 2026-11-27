package fr.kinwaypoint.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory

/**
 * Lecture d'un QR code en portrait : la caméra remplit l'écran, assombri sauf un
 * cadre carré au centre (seule zone lue), avec une ligne qui balaie le cadre,
 * une lampe et un bouton fermer. Remplace l'écran zxing par défaut, en paysage.
 */
class QrScanActivity : ComponentActivity() {

    /** Contrat d'appel : rend le texte lu, ou null si annulé. */
    class Scan : ActivityResultContract<String, String?>() {
        override fun createIntent(context: android.content.Context, input: String) =
            Intent(context, QrScanActivity::class.java).putExtra(EXTRA_PROMPT, input)

        override fun parseResult(resultCode: Int, intent: Intent?): String? =
            if (resultCode == Activity.RESULT_OK) intent?.getStringExtra(EXTRA_TEXT) else null
    }

    private var granted by mutableStateOf(false)
    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!granted) askCamera.launch(Manifest.permission.CAMERA)
        val prompt = intent.getStringExtra(EXTRA_PROMPT).orEmpty()
        setContent {
            KinWaypointTheme {
                ScanScreen(
                    prompt = prompt,
                    granted = granted,
                    onAskCamera = { askCamera.launch(Manifest.permission.CAMERA) },
                    onResult = { text ->
                        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_TEXT, text))
                        finish()
                    },
                    onClose = { setResult(Activity.RESULT_CANCELED); finish() },
                )
            }
        }
    }

    companion object {
        private const val EXTRA_PROMPT = "prompt"
        private const val EXTRA_TEXT = "text"
    }
}

/** Taille du cadre de visée : 68 % de la largeur, entre 220 et 300 dp. */
private fun frameSizeDp(screenWidthDp: Int) = (screenWidthDp * 0.68f).coerceIn(220f, 300f)

@Composable
private fun ScanScreen(
    prompt: String,
    granted: Boolean,
    onAskCamera: () -> Unit,
    onResult: (String) -> Unit,
    onClose: () -> Unit,
) {
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val screenWidthDp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val frameDp = frameSizeDp(screenWidthDp)
    val framePx = with(LocalDensity.current) { frameDp.dp.roundToPx() }
    var torch by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf<BarcodeView?>(null) }
    var done by remember { mutableStateOf(false) }

    // Caméra active seulement quand l'écran est visible.
    DisposableEffect(lifecycle, view, granted) {
        val v = view
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> if (granted) v?.resume()
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> v?.pause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (granted && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) v?.resume()
        onDispose {
            lifecycle.removeObserver(observer)
            v?.pause()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    BarcodeView(context).apply {
                        decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
                        // Seul le carré central est analysé : il correspond au cadre dessiné.
                        framingRectSize = com.journeyapps.barcodescanner.Size(framePx, framePx)
                        decodeContinuous(object : com.journeyapps.barcodescanner.BarcodeCallback {
                            override fun barcodeResult(result: com.journeyapps.barcodescanner.BarcodeResult) {
                                val text = result.text
                                if (!done && !text.isNullOrBlank()) {
                                    done = true
                                    onResult(text)
                                }
                            }

                            override fun possibleResultPoints(resultPoints: MutableList<com.google.zxing.ResultPoint>) = Unit
                        })
                        view = this
                    }
                },
            )
        }

        // Voile sombre percé d'un carré arrondi, coins lumineux et ligne de balayage.
        val sweep = rememberInfiniteTransition(label = "balayage")
        val progress by sweep.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Reverse),
            label = "ligne",
        )
        val accent = AppTheme.colors.success
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            val side = frameDp.dp.toPx()
            val left = (size.width - side) / 2
            val top = (size.height - side) / 2
            val radius = 28.dp.toPx()
            drawRect(Color.Black.copy(alpha = 0.62f))
            drawRoundRect(
                Color.Transparent,
                topLeft = Offset(left, top),
                size = Size(side, side),
                cornerRadius = CornerRadius(radius, radius),
                blendMode = BlendMode.Clear,
            )
            // Coins du cadre.
            val arm = side * 0.16f
            val stroke = 5.dp.toPx()
            fun corner(x: Float, y: Float, dx: Float, dy: Float) {
                drawLine(Color.White, Offset(x, y + dy * radius), Offset(x, y + dy * (radius + arm)), stroke, StrokeCap.Round)
                drawLine(Color.White, Offset(x + dx * radius, y), Offset(x + dx * (radius + arm), y), stroke, StrokeCap.Round)
                drawArc(
                    Color.White,
                    startAngle = when { dx > 0 && dy > 0 -> 180f; dx < 0 && dy > 0 -> 270f; dx < 0 && dy < 0 -> 0f; else -> 90f },
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset(if (dx > 0) x else x - 2 * radius, if (dy > 0) y else y - 2 * radius),
                    size = Size(2 * radius, 2 * radius),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
            corner(left, top, 1f, 1f)
            corner(left + side, top, -1f, 1f)
            corner(left, top + side, 1f, -1f)
            corner(left + side, top + side, -1f, -1f)
            // Ligne qui balaie le cadre.
            val y = top + 18.dp.toPx() + (side - 36.dp.toPx()) * progress
            drawLine(accent.copy(alpha = 0.9f), Offset(left + 22.dp.toPx(), y), Offset(left + side - 22.dp.toPx(), y), 3.dp.toPx(), StrokeCap.Round)
        }

        // Haut : fermer et lampe.
        Row(
            Modifier.fillMaxWidth().systemBarsPadding().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            FilledTonalIconButton(
                onClick = onClose,
                colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color.White.copy(alpha = 0.18f), contentColor = Color.White),
            ) { Icon(Icons.Default.Close, contentDescription = "Fermer") }
            if (granted) {
                FilledTonalIconButton(
                    onClick = { torch = !torch; view?.setTorch(torch) },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = if (torch) Color.White else Color.White.copy(alpha = 0.18f),
                        contentColor = if (torch) Color.Black else Color.White,
                    ),
                ) { Icon(if (torch) Icons.Default.FlashlightOn else Icons.Default.FlashlightOff, contentDescription = "Lampe") }
            }
        }

        // Texte sous le cadre.
        Column(
            Modifier.align(Alignment.Center).padding(top = frameDp.dp + 56.dp).padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (granted) prompt.ifBlank { "Place le QR code dans le cadre" } else "La caméra est nécessaire pour lire le QR code.",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            if (granted) {
                Text("La lecture est automatique.", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, textAlign = TextAlign.Center)
            } else {
                Spacer(Modifier.height(4.dp))
                Button(onClick = onAskCamera, shape = CircleShape) { Text("Autoriser la caméra") }
            }
        }
    }
}
