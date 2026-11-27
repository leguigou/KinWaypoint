package fr.kinwaypoint.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Saisie d'un code à 8 chiffres en 8 cases (4 + 4). Accepte un collage
 * (« 4821 0937 », espaces et tirets ignorés) et appelle [onComplete] dès
 * que les 8 chiffres sont là : pas de bouton « Valider » à chercher.
 */
@Composable
fun CodeBoxes(
    value: String,
    onValueChange: (String) -> Unit,
    onComplete: (String) -> Unit,
    enabled: Boolean = true,
    length: Int = 8,
) {
    val clipboard = LocalClipboardManager.current
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    fun accept(raw: String) {
        val digits = raw.filter(Char::isDigit).take(length)
        onValueChange(digits)
        if (digits.length == length) onComplete(digits)
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        BasicTextField(
            value = value,
            onValueChange = { accept(it) },
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                Box {
                    // Champ réel invisible : il reçoit le clavier et le collage.
                    Box(Modifier.alpha(0f).size(1.dp)) { inner() }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        for (i in 0 until length) {
                            if (i == length / 2) Spacer(Modifier.width(12.dp))
                            else if (i > 0) Spacer(Modifier.width(6.dp))
                            val char = value.getOrNull(i)
                            val current = focused && enabled && i == value.length.coerceAtMost(length - 1) && value.length < length
                            val filled = char != null
                            Box(
                                Modifier.weight(1f).height(54.dp)
                                    .background(
                                        if (filled) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                                        RoundedCornerShape(12.dp),
                                    )
                                    .border(
                                        if (current) 2.dp else 1.dp,
                                        when {
                                            current -> MaterialTheme.colorScheme.primary
                                            filled -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                                            else -> MaterialTheme.colorScheme.outlineVariant
                                        },
                                        RoundedCornerShape(12.dp),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    char?.toString() ?: "",
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            },
        )
        TextButton(
            onClick = { clipboard.getText()?.text?.let { accept(it) } },
            enabled = enabled,
        ) {
            Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Coller le code")
        }
    }
}
