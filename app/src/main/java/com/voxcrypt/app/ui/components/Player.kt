package com.voxcrypt.app.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.voxcrypt.app.audio.AudioPlayer
import com.voxcrypt.app.ui.theme.Vx
import com.voxcrypt.app.ui.util.Fmt

@Composable
fun PlayerBar(state: AudioPlayer.State, onToggle: () -> Unit, onSeek: (Float) -> Unit) {
    if (state.error != null) {
        Notice(state.error, Vx.Danger, VxIcons.Warning)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier.size(48.dp).clip(CutCornerShape(8.dp)).border(1.dp, Vx.Cyan, CutCornerShape(8.dp))
                .clickable(enabled = state.loaded, role = Role.Button, onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (state.playing) VxIcons.Pause else VxIcons.Play,
                contentDescription = if (state.playing) "Pause" else "Lecture",
                tint = Vx.Cyan, modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            val dur = state.durationMillis.coerceAtLeast(1)
            Slider(
                value = state.positionMillis.toFloat() / dur,
                onValueChange = onSeek,
                enabled = state.loaded,
                colors = SliderDefaults.colors(
                    thumbColor = Vx.Cyan, activeTrackColor = Vx.Cyan, inactiveTrackColor = Vx.Line,
                ),
            )
            Row {
                Text(Fmt.clock(state.positionMillis.toLong()), style = MaterialTheme.typography.labelSmall, color = Vx.Silver)
                Spacer(Modifier.weight(1f))
                Text(Fmt.clock(state.durationMillis.toLong()), style = MaterialTheme.typography.labelSmall, color = Vx.SilverDim)
            }
        }
    }
}
