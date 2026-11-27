package fr.kinwaypoint.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/** Couleurs de l'arc-en-ciel, pour la barre de teinte et la pastille « personnalisée ». */
private val Rainbow = listOf(
    Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00),
    Color(0xFF00FFFF), Color(0xFF0000FF), Color(0xFFFF00FF), Color(0xFFFF0000),
)

/** « #RRGGBB » en majuscules. */
fun Color.toHex(): String = String.format("#%06X", 0xFFFFFF and toArgb())

private fun hsvColor(hue: Float, sat: Float, value: Float) =
    Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)))

/**
 * Pastille « couleur personnalisée » à mettre après la palette : arc-en-ciel avec
 * une pipette, ou la couleur choisie (cochée) si elle n'est pas dans la palette.
 */
@Composable
fun CustomColorSwatch(custom: Color?, size: Dp, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .then(if (custom != null) Modifier.background(custom) else Modifier.background(Brush.sweepGradient(Rainbow)))
            .border(if (custom != null) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (custom != null) {
            Icon(Icons.Default.Check, contentDescription = "Couleur personnalisée", tint = if (custom.luminance() > 0.6f) Color.Black else Color.White)
        } else {
            Box(Modifier.size(size * 0.55f).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Colorize, contentDescription = "Couleur personnalisée", tint = Color.Black, modifier = Modifier.size(size * 0.35f))
            }
        }
    }
}

/**
 * Sélecteur de couleur façon logiciel de dessin, dans une fenêtre :
 * carré saturation / luminosité, barre de teinte, aperçu avant / après,
 * code hexadécimal et couleurs rapides.
 */
@Composable
fun ColorPickerDialog(initial: Color, title: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val start = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toArgb(), it) } }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var sat by remember { mutableFloatStateOf(start[1]) }
    var value by remember { mutableFloatStateOf(start[2]) }
    val current = hsvColor(hue, sat, value)
    var hexText by remember { mutableStateOf(initial.toHex()) }

    fun setColor(color: Color) {
        val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(color.toArgb(), it) }
        hue = hsv[0]; sat = hsv[1]; value = hsv[2]
        hexText = color.toHex()
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)

                // Carré saturation (gauche → droite) / luminosité (haut → bas).
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.25f)
                        .clip(RoundedCornerShape(18.dp))
                        .pointerInput(Unit) {
                            fun update(p: Offset) {
                                sat = (p.x / size.width).coerceIn(0f, 1f)
                                value = 1f - (p.y / size.height).coerceIn(0f, 1f)
                                hexText = hsvColor(hue, sat, value).toHex()
                            }
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                update(down.position)
                                drag(down.id) { change -> update(change.position); change.consume() }
                            }
                        }
                        .drawBehind {
                            drawRect(Brush.horizontalGradient(listOf(Color.White, hsvColor(hue, 1f, 1f))))
                            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                            val c = Offset(sat * size.width, (1f - value) * size.height)
                            drawCircle(Color.Black.copy(alpha = 0.25f), radius = 14.dp.toPx(), center = c + Offset(0f, 1.5f.dp.toPx()))
                            drawCircle(current, radius = 12.dp.toPx(), center = c)
                            drawCircle(Color.White, radius = 12.dp.toPx(), center = c, style = Stroke(3.dp.toPx()))
                        },
                )

                // Barre de teinte.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                        .pointerInput(Unit) {
                            fun update(p: Offset) {
                                hue = (p.x / size.width).coerceIn(0f, 1f) * 360f
                                hexText = hsvColor(hue, sat, value).toHex()
                            }
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                update(down.position)
                                drag(down.id) { change -> update(change.position); change.consume() }
                            }
                        }
                        .drawBehind {
                            val r = size.height / 2
                            drawRoundRect(
                                Brush.horizontalGradient(Rainbow),
                                topLeft = Offset(0f, size.height * 0.2f),
                                size = androidx.compose.ui.geometry.Size(size.width, size.height * 0.6f),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
                            )
                            val c = Offset((hue / 360f) * size.width, size.height / 2)
                            drawCircle(Color.Black.copy(alpha = 0.25f), radius = r, center = c + Offset(0f, 1.5f.dp.toPx()))
                            drawCircle(hsvColor(hue, 1f, 1f), radius = r - 1.dp.toPx(), center = c)
                            drawCircle(Color.White, radius = r - 1.dp.toPx(), center = c, style = Stroke(3.dp.toPx()))
                        },
                )

                // Aperçu avant / après et code hexadécimal.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(Modifier.width(104.dp).height(48.dp).clip(RoundedCornerShape(14.dp))) {
                            Box(Modifier.weight(1f).fillMaxHeight().background(initial).clickable { setColor(initial) })
                            Box(Modifier.weight(1f).fillMaxHeight().background(current))
                        }
                        Text("avant · après", style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.padding(top = 4.dp))
                    }
                    OutlinedTextField(
                        value = hexText,
                        onValueChange = { text ->
                            val clean = "#" + text.uppercase().filter { it in "0123456789ABCDEF" }.take(6)
                            hexText = clean
                            parseHex(clean)?.let { c ->
                                val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(c.toArgb(), it) }
                                hue = hsv[0]; sat = hsv[1]; value = hsv[2]
                            }
                        },
                        label = { Text("Code") },
                        singleLine = true,
                        isError = parseHex(hexText) == null,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f),
                    )
                }

                // Couleurs rapides.
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ProfilePalette.forEach { hex ->
                        val c = parseHex(hex)!!
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(c)
                                .border(2.dp, if (c.toHex() == current.toHex()) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
                                .clickable { setColor(c) },
                        )
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("Annuler") }
                    Button(onClick = { onPick(current.toHex()) }, shape = CircleShape) { Text("Choisir") }
                }
            }
        }
    }
}
