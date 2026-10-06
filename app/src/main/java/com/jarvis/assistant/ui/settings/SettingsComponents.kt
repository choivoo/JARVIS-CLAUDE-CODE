package com.jarvis.assistant.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.ui.components.GlassPanel
import com.jarvis.assistant.ui.theme.hudColors
import kotlinx.coroutines.delay

@Composable
fun SectionHeader(title: String) {
    val colors = hudColors()
    Text(
        title,
        color = colors.accent,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        letterSpacing = 3.sp,
        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
    )
}

@Composable
fun SettingsCard(content: @Composable () -> Unit) {
    GlassPanel(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = hudColors()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = colors.text, fontSize = 14.sp)
            if (subtitle != null) Text(subtitle, color = colors.textDim, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = colors.accent, checkedThumbColor = Color.White),
        )
    }
}

@Composable
fun DropdownRow(
    title: String,
    selectedLabel: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    val colors = hudColors()
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = colors.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { open = true }) {
                Text(selectedLabel, color = colors.accentSoft, fontSize = 12.sp, maxLines = 1)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (value, label) ->
                    DropdownMenuItem(
                        text = { Text(label, fontSize = 13.sp) },
                        onClick = {
                            open = false
                            onSelect(value)
                        },
                    )
                }
            }
        }
    }
}

/** Text field that saves itself shortly after typing stops. [resetKey] reloads it when the context changes. */
@Composable
fun TextFieldRow(
    label: String,
    value: String,
    resetKey: Any,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    onCommit: (String) -> Unit,
) {
    val colors = hudColors()
    var text by remember(resetKey) { mutableStateOf(value) }
    LaunchedEffect(text) {
        if (text != value) {
            delay(600)
            onCommit(text.trim())
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        placeholder = { Text(placeholder, color = colors.textDim) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
        colors = fieldColors(),
    )
}

/** API key entry: the stored key is never shown or read back into the UI. */
@Composable
fun SecretRow(label: String, hasKey: Boolean, resetKey: Any, onSave: (String) -> Unit, onRemove: () -> Unit) {
    val colors = hudColors()
    var text by remember(resetKey) { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(label) },
            placeholder = { Text(if (hasKey) "●●●●●●●● saved securely" else "Paste key", color = colors.textDim) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    onSave(text)
                    text = ""
                },
                enabled = text.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = Color.Black),
            ) { Text("SAVE KEY", fontSize = 12.sp, letterSpacing = 1.sp) }
            if (hasKey) {
                OutlinedButton(onClick = onRemove) { Text("REMOVE", fontSize = 12.sp, color = colors.textDim) }
            }
            Text(
                if (hasKey) "Encrypted with Android Keystore" else "No key stored",
                color = colors.textDim,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onCommit: (Float) -> Unit,
) {
    val colors = hudColors()
    var live by remember(value) { mutableStateOf(value) }
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(title, color = colors.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(format(live), color = colors.accentSoft, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        }
        Slider(
            value = live,
            onValueChange = { live = it },
            onValueChangeFinished = { onCommit(live) },
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = colors.accent, activeTrackColor = colors.accent),
        )
    }
}

@Composable
fun PermissionRow(title: String, description: String, granted: Boolean, actionLabel: String = "GRANT", onGrant: () -> Unit) {
    val colors = hudColors()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, color = colors.text, fontSize = 14.sp)
            Text(description, color = colors.textDim, fontSize = 11.sp)
        }
        if (granted) {
            Text("GRANTED", color = colors.accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
        } else {
            OutlinedButton(onClick = onGrant) { Text(actionLabel, fontSize = 11.sp, color = colors.accentSoft) }
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = hudColors().accent,
    unfocusedBorderColor = hudColors().accent.copy(alpha = 0.35f),
    focusedLabelColor = hudColors().accent,
    unfocusedLabelColor = hudColors().textDim,
    cursorColor = hudColors().accent,
    focusedTextColor = hudColors().text,
    unfocusedTextColor = hudColors().text,
)
