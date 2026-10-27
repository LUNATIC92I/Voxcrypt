package com.voxcrypt.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.voxcrypt.app.crypto.PasswordPolicy
import com.voxcrypt.app.ui.theme.Vx

@Composable
fun vxFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Vx.Cyan,
    unfocusedBorderColor = Vx.LineBright,
    focusedLabelColor = Vx.Cyan,
    unfocusedLabelColor = Vx.SilverDim,
    cursorColor = Vx.Cyan,
    focusedTextColor = Vx.Silver,
    unfocusedTextColor = Vx.Silver,
    focusedContainerColor = Vx.Black.copy(alpha = 0.6f),
    unfocusedContainerColor = Vx.Black.copy(alpha = 0.6f),
    errorBorderColor = Vx.Danger,
)

@Composable
fun VxTextField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        shape = CutCornerShape(6.dp),
        colors = vxFieldColors(),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * Password input: masked by default, no autocorrect or suggestions
 * (KeyboardType.Password asks keyboards not to learn the input).
 */
@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        shape = CutCornerShape(6.dp),
        colors = vxFieldColors(),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = imeAction),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) VxIcons.EyeOff else VxIcons.Eye,
                    contentDescription = if (visible) "Masquer le mot de passe" else "Afficher le mot de passe",
                    tint = Vx.SilverDim,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun StrengthMeter(strength: PasswordPolicy.Strength) {
    val (color, level) = when (strength) {
        PasswordPolicy.Strength.TOO_SHORT -> Vx.Danger to 1
        PasswordPolicy.Strength.WEAK -> Vx.Warning to 2
        PasswordPolicy.Strength.MEDIUM -> Vx.Blue to 3
        PasswordPolicy.Strength.STRONG -> Vx.Cyan to 4
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            for (i in 1..4) {
                Box(
                    Modifier.weight(1f).height(4.dp)
                        .background(if (i <= level) color else Vx.Line, CutCornerShape(1.dp)),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ROBUSTESSE : ", style = MaterialTheme.typography.labelSmall, color = Vx.SilverDim)
            Text(strength.label.uppercase(), style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}

@Composable
fun ChoiceRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit, color: Color = Vx.Cyan) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, label ->
            val active = i == selected
            Box(Modifier.weight(1f)) {
                NeonButton(
                    text = label,
                    onClick = { onSelect(i) },
                    kind = if (active) ButtonKind.Primary else ButtonKind.Secondary,
                    height = 42.dp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
