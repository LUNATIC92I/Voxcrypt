package com.voxcrypt.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voxcrypt.app.ui.theme.Vx

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Vx.SurfaceHigh,
        shape = CutCornerShape(topStart = 16.dp, bottomEnd = 16.dp),
        title = { Text(title.uppercase(), style = MaterialTheme.typography.titleSmall, color = if (danger) Vx.Danger else Vx.Cyan) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium, color = Vx.Silver) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel.uppercase(), color = if (danger) Vx.Danger else Vx.Cyan, style = MaterialTheme.typography.labelLarge) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("ANNULER", color = Vx.SilverDim, style = MaterialTheme.typography.labelLarge) }
        },
    )
}

@Composable
fun RenameDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Vx.SurfaceHigh,
        shape = CutCornerShape(topStart = 16.dp, bottomEnd = 16.dp),
        title = { Text("RENOMMER", style = MaterialTheme.typography.titleSmall, color = Vx.Cyan) },
        text = { VxTextField(text, { text = it }, "Nouveau nom", Modifier.fillMaxWidth()) },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) {
                Text("VALIDER", color = Vx.Cyan, style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("ANNULER", color = Vx.SilverDim, style = MaterialTheme.typography.labelLarge) }
        },
    )
}

@Composable
fun InfoDialog(title: String, lines: List<Pair<String, String>>, onDismiss: () -> Unit, footer: String? = null) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Vx.SurfaceHigh,
        shape = CutCornerShape(topStart = 16.dp, bottomEnd = 16.dp),
        title = { Text(title.uppercase(), style = MaterialTheme.typography.titleSmall, color = Vx.Cyan) },
        text = {
            Column {
                lines.forEach { (k, v) -> InfoLine(k, v) }
                if (footer != null) {
                    Text(footer, style = MaterialTheme.typography.bodySmall, color = Vx.SilverDim, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("FERMER", color = Vx.Cyan, style = MaterialTheme.typography.labelLarge) }
        },
    )
}

