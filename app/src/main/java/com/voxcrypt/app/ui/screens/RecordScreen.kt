package com.voxcrypt.app.ui.screens

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voxcrypt.app.VoxcryptApp
import com.voxcrypt.app.audio.AudioPlayer
import com.voxcrypt.app.audio.VoiceRecorder
import com.voxcrypt.app.data.PendingRecording
import com.voxcrypt.app.ui.components.ButtonKind
import com.voxcrypt.app.ui.components.ConfirmDialog
import com.voxcrypt.app.ui.components.HudPanel
import com.voxcrypt.app.ui.components.InfoLine
import com.voxcrypt.app.ui.components.NeonButton
import com.voxcrypt.app.ui.components.Notice
import com.voxcrypt.app.ui.components.PlayerBar
import com.voxcrypt.app.ui.components.RenameDialog
import com.voxcrypt.app.ui.components.VxIcons
import com.voxcrypt.app.ui.components.VxScaffold
import com.voxcrypt.app.ui.components.Waveform
import com.voxcrypt.app.ui.theme.Vx
import com.voxcrypt.app.ui.util.Errors
import com.voxcrypt.app.ui.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecordViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as VoxcryptApp).container
    private val recorder = c.recorder
    val state = recorder.state
    val levels = recorder.levels
    private val player = AudioPlayer(viewModelScope)
    val playerState = player.state

    private val _current = MutableStateFlow<PendingRecording?>(null)
    val current = _current.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    init {
        viewModelScope.launch {
            recorder.state.collect { s ->
                if (s is VoiceRecorder.State.Finished && _current.value?.file != s.recording.file) {
                    _current.value = s.recording
                    player.load(s.recording.file)
                    c.notifyVaultChanged()
                }
            }
        }
    }

    fun start() {
        player.release()
        _current.value = null
        _message.value = null
        recorder.start()
    }

    fun pause() = recorder.pause()
    fun resume() = recorder.resume()
    fun stop() = recorder.stop()
    fun discard() {
        recorder.discard()
        _current.value = null
    }

    fun togglePlay() = player.toggle()
    fun seek(f: Float) = player.seekTo(f)

    fun rename(newName: String) {
        val rec = _current.value ?: return
        player.release()
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { c.vault.renamePending(rec.file, newName) }
                val updated = rec.copy(file = file, name = file.nameWithoutExtension)
                _current.value = updated
                player.load(updated.file)
                c.notifyVaultChanged()
            } catch (e: Exception) {
                _message.value = Errors.describe(e)
                player.load(rec.file)
            }
        }
    }

    fun delete() {
        val rec = _current.value ?: return
        player.release()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { c.vault.deletePending(rec.file) }
            _current.value = null
            recorder.acknowledge()
            c.notifyVaultChanged()
            _message.value = "Enregistrement supprimé."
        }
    }

    fun newRecording() {
        player.release()
        _current.value = null
        _message.value = null
        recorder.acknowledge()
    }

    fun pausePlayback() = player.pause()

    override fun onCleared() {
        player.release()
    }
}

@Composable
fun RecordScreen(
    onBack: () -> Unit,
    onEncrypt: (PendingRecording) -> Unit,
    vm: RecordViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val levels by vm.levels.collectAsStateWithLifecycle()
    val current by vm.current.collectAsStateWithLifecycle()
    val player by vm.playerState.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (!ok) {
            val activity = context as? Activity
            permanentlyDenied = activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
        } else {
            vm.start()
        }
    }

    // Stop playback when the screen leaves the foreground.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) vm.pausePlayback() }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val recordingActive = state is VoiceRecorder.State.Recording || state is VoiceRecorder.State.Paused
    var confirmLeave by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    BackHandler(enabled = recordingActive) { confirmLeave = true }

    VxScaffold(
        title = "Enregistrement",
        subtitle = "CAPTURE VOCALE PRIVÉE",
        onBack = { if (recordingActive) confirmLeave = true else onBack() },
    ) {
        if (!granted) {
            HudPanel(label = "Autorisation requise", accent = Vx.Warning) {
                Text(
                    "VOXCRYPT a besoin du microphone pour enregistrer votre voix. L'audio reste sur cet appareil, dans l'espace privé de l'application.",
                    style = MaterialTheme.typography.bodyMedium, color = Vx.Silver,
                )
                if (permanentlyDenied) {
                    Notice("L'autorisation a été refusée. Activez le microphone dans les paramètres Android de l'application.", Vx.Warning, VxIcons.Warning)
                    NeonButton("Ouvrir les paramètres", {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                        )
                    }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
                } else {
                    NeonButton("Autoriser le microphone", { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }, Modifier.fillMaxWidth(), VxIcons.Mic)
                }
            }
            return@VxScaffold
        }

        val (elapsed, label, color) = when (val s = state) {
            is VoiceRecorder.State.Recording -> Triple(s.elapsedMillis, "ENREGISTREMENT", Vx.Danger)
            is VoiceRecorder.State.Paused -> Triple(s.elapsedMillis, "EN PAUSE", Vx.Warning)
            is VoiceRecorder.State.Finished -> Triple(s.recording.durationMillis, "TERMINÉ", Vx.Cyan)
            else -> Triple(0L, "PRÊT", Vx.SilverDim)
        }

        HudPanel(accent = color) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BlinkDot(color, state is VoiceRecorder.State.Recording)
                Text("  $label", style = MaterialTheme.typography.labelLarge, color = color)
            }
            Text(
                Fmt.timer(elapsed),
                style = TextStyle(fontFamily = Vx.Mono, fontSize = 46.sp, fontWeight = FontWeight.Light, letterSpacing = 4.sp),
                color = Vx.Silver,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Waveform(levels, active = state is VoiceRecorder.State.Recording)
        }

        (state as? VoiceRecorder.State.Paused)?.reason?.let { Notice(it, Vx.Warning, VxIcons.Pause) }
        (state as? VoiceRecorder.State.Error)?.let { Notice(it.message, Vx.Danger, VxIcons.Warning) }
        message?.let { Notice(it) }

        // Controls
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (state) {
                is VoiceRecorder.State.Recording -> {
                    RoundControl(VxIcons.Pause, "Mettre en pause", Vx.Warning) { vm.pause() }
                    RoundControl(VxIcons.Stop, "Arrêter", Vx.Cyan, big = true) { vm.stop() }
                }
                is VoiceRecorder.State.Paused -> {
                    RoundControl(VxIcons.Record, "Reprendre", Vx.Danger) { vm.resume() }
                    RoundControl(VxIcons.Stop, "Arrêter", Vx.Cyan, big = true) { vm.stop() }
                }
                is VoiceRecorder.State.Finished -> Unit
                else -> RoundControl(VxIcons.Mic, "Démarrer l'enregistrement", Vx.Danger, big = true) { vm.start() }
            }
        }
        if (recordingActive) {
            NeonButton("Abandonner sans conserver", { vm.discard() }, Modifier.fillMaxWidth(), VxIcons.Close, kind = ButtonKind.Danger, height = 44.dp)
        }

        val rec = current
        if (rec != null && state is VoiceRecorder.State.Finished) {
            HudPanel(label = "Réécoute avant chiffrement") {
                InfoLine("Nom", rec.name)
                InfoLine("Durée", Fmt.clock(rec.durationMillis))
                InfoLine("Taille", Fmt.size(rec.file.length()))
                InfoLine("Format", "AAC / MPEG-4 (.m4a)")
                PlayerBar(player, vm::togglePlay, vm::seek)
                Notice(
                    "Cet enregistrement est encore en clair dans l'espace privé de l'application. Chiffrez-le : la copie en clair sera détruite après vérification.",
                    Vx.Warning, VxIcons.Warning,
                )
                NeonButton("Chiffrer maintenant", { vm.pausePlayback(); onEncrypt(rec) }, Modifier.fillMaxWidth(), VxIcons.Lock)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NeonButton("Renommer", { showRename = true }, Modifier.weight(1f), VxIcons.Edit, kind = ButtonKind.Secondary, height = 44.dp)
                    NeonButton("Supprimer", { confirmDelete = true }, Modifier.weight(1f), VxIcons.Delete, kind = ButtonKind.Danger, height = 44.dp)
                }
                NeonButton("Nouvel enregistrement", { vm.newRecording() }, Modifier.fillMaxWidth(), VxIcons.Mic, kind = ButtonKind.Secondary, height = 44.dp)
            }
        } else if (!recordingActive) {
            Notice("Format AAC mono 44,1 kHz. L'enregistrement se met en pause automatiquement si l'application passe en arrière-plan.")
        }
    }

    if (confirmLeave) {
        ConfirmDialog(
            title = "Enregistrement en cours",
            message = "Arrêter l'enregistrement et le conserver avant de quitter cet écran ?",
            confirmLabel = "Arrêter et conserver",
            onConfirm = { confirmLeave = false; vm.stop() },
            onDismiss = { confirmLeave = false },
        )
    }
    if (showRename && current != null) {
        RenameDialog(current!!.name, { showRename = false; vm.rename(it) }, { showRename = false })
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Supprimer l'enregistrement",
            message = "L'enregistrement non chiffré sera effacé définitivement de l'espace privé de l'application.",
            confirmLabel = "Supprimer",
            danger = true,
            onConfirm = { confirmDelete = false; vm.delete() },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun BlinkDot(color: Color, blinking: Boolean) {
    val t = rememberInfiniteTransition(label = "blink")
    val a by t.animateFloat(1f, 0.15f, infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(10.dp).alpha(if (blinking) a else 1f).background(color, CircleShape))
}

@Composable
private fun RoundControl(icon: ImageVector, label: String, color: Color, big: Boolean = false, onClick: () -> Unit) {
    val size = if (big) 86.dp else 64.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(size).clip(CircleShape)
                .background(color.copy(alpha = 0.1f))
                .border(1.5.dp, color, CircleShape)
                .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(if (big) 34.dp else 26.dp))
        }
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Vx.SilverDim, modifier = Modifier.alpha(0.9f))
    }
}
