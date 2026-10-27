package com.voxcrypt.app.ui.screens

import android.app.Application
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voxcrypt.app.VoxcryptApp
import com.voxcrypt.app.audio.AudioInfo
import com.voxcrypt.app.audio.AudioPlayer
import com.voxcrypt.app.crypto.KeyProtection
import com.voxcrypt.app.crypto.VxcHeader
import com.voxcrypt.app.data.DecryptedAudio
import com.voxcrypt.app.data.Phase
import com.voxcrypt.app.data.Progress
import com.voxcrypt.app.data.UnlockChoice
import com.voxcrypt.app.ui.components.ButtonKind
import com.voxcrypt.app.ui.components.ConfirmDialog
import com.voxcrypt.app.ui.components.HudPanel
import com.voxcrypt.app.ui.components.InfoLine
import com.voxcrypt.app.ui.components.NeonButton
import com.voxcrypt.app.ui.components.Notice
import com.voxcrypt.app.ui.components.PasswordField
import com.voxcrypt.app.ui.components.PlayerBar
import com.voxcrypt.app.ui.components.VxIcons
import com.voxcrypt.app.ui.components.VxProgress
import com.voxcrypt.app.ui.components.VxScaffold
import com.voxcrypt.app.ui.theme.Vx
import com.voxcrypt.app.ui.util.CreateDocumentWithType
import com.voxcrypt.app.ui.util.Documents
import com.voxcrypt.app.ui.util.Errors
import com.voxcrypt.app.ui.util.Fmt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

class DecryptViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as VoxcryptApp).container
    private val player = AudioPlayer(viewModelScope)
    val playerState = player.state

    data class Target(
        val displayName: String,
        val sizeBytes: Long,
        val vaultName: String?,
        val uri: Uri?,
        val header: VxcHeader?,
        val headerError: String?,
    )

    sealed interface Op {
        data object Idle : Op
        data class Running(val progress: Progress) : Op
        data class Success(val audio: DecryptedAudio) : Op
        data class Failed(val message: String) : Op
    }

    private val _target = MutableStateFlow<Target?>(null)
    val target = _target.asStateFlow()
    private val _op = MutableStateFlow<Op>(Op.Idle)
    val op = _op.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    val notice = _notice.asStateFlow()
    private var job: Job? = null

    private fun opener(t: Target): () -> InputStream {
        val ctx = getApplication<Application>()
        return if (t.vaultName != null) {
            val file = c.vault.findVault(t.vaultName)?.file
            { file?.inputStream() ?: throw java.io.FileNotFoundException() }
        } else {
            { Documents.openInput(ctx, t.uri!!) }
        }
    }

    fun loadVault(name: String) = load {
        val e = c.vault.findVault(name) ?: throw java.io.FileNotFoundException()
        Target(e.name, e.sizeBytes, name, null, null, null)
    }

    fun loadUri(uri: Uri) = load {
        val d = AudioInfo.details(getApplication(), uri)
        Target(d.displayName, d.sizeBytes, null, uri, null, null)
    }

    private fun load(make: () -> Target) {
        clear()
        viewModelScope.launch(Dispatchers.IO) {
            val base = try {
                make()
            } catch (e: Exception) {
                _op.value = Op.Failed(Errors.describe(e))
                return@launch
            }
            // Signature, version and parameter limits are checked here, before any key derivation.
            _target.value = try {
                base.copy(header = c.vault.inspect(opener(base)))
            } catch (e: Exception) {
                base.copy(headerError = Errors.describe(e))
            }
        }
    }

    fun decrypt(password: CharArray?) {
        val t = _target.value ?: return
        if (t.header == null || _op.value is Op.Running) return
        player.release()
        discardCurrent()
        job = viewModelScope.launch(Dispatchers.Default) {
            val self = coroutineContext
            _op.value = Op.Running(Progress(Phase.DERIVING_KEY, null))
            try {
                val unlock = if (t.header.protection == KeyProtection.DEVICE) UnlockChoice.Device(c.deviceKey)
                else UnlockChoice.Password(password!!)
                val audio = c.vault.decrypt(opener(t), t.sizeBytes, unlock) { p ->
                    self.ensureActive()
                    _op.value = Op.Running(p)
                }
                _op.value = Op.Success(audio)
                withContext(Dispatchers.Main) { player.load(audio.file) }
            } catch (e: CancellationException) {
                _op.value = Op.Failed("Opération annulée. Les données partielles ont été effacées.")
            } catch (e: Throwable) {
                _op.value = Op.Failed(Errors.describe(e))
            } finally {
                password?.fill('\u0000')
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun togglePlay() = player.toggle()
    fun seek(f: Float) = player.seekTo(f)
    fun pausePlayback() = player.pause()

    fun exportAudio(uri: Uri) {
        val audio = (_op.value as? Op.Success)?.audio ?: return
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            _notice.value = try {
                withContext(Dispatchers.IO) { Documents.openOutput(ctx, uri).use { c.vault.exportDecrypted(audio, it) } }
                "Audio exporté. Cette copie n'est plus protégée par VOXCRYPT."
            } catch (e: Exception) {
                withContext(Dispatchers.IO) { Documents.deleteQuietly(ctx, uri) }
                Errors.describe(e)
            }
        }
    }

    fun importToVault() {
        val t = _target.value ?: return
        if (t.uri == null) return
        viewModelScope.launch {
            _notice.value = try {
                val e = withContext(Dispatchers.IO) { c.vault.importVxc(opener(t), t.displayName, t.sizeBytes) }
                c.notifyVaultChanged()
                "Fichier chiffré ajouté à vos fichiers sécurisés sous le nom « ${e.name} »."
            } catch (e: Exception) {
                Errors.describe(e)
            }
        }
    }

    /** Destroys the decrypted copy owned by this screen, if any. */
    private fun discardCurrent() {
        val audio = (_op.value as? Op.Success)?.audio ?: return
        c.appScope.launch(Dispatchers.IO) { c.vault.discardDecrypted(audio) }
    }

    /** Destroys the decrypted copy and resets the screen state. */
    fun clear() {
        job?.cancel()
        player.release()
        discardCurrent()
        _op.value = Op.Idle
        _notice.value = null
    }

    fun wipeDecrypted() {
        player.release()
        discardCurrent()
        _op.value = Op.Idle
        _notice.value = "Copie déchiffrée effacée."
    }

    override fun onCleared() {
        job?.cancel()
        player.release()
        discardCurrent()
    }
}

@Composable
fun DecryptScreen(
    vaultName: String?,
    uri: Uri?,
    onBack: () -> Unit,
    vm: DecryptViewModel = viewModel(),
) {
    val target by vm.target.collectAsStateWithLifecycle()
    val op by vm.op.collectAsStateWithLifecycle()
    val player by vm.playerState.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()

    LaunchedEffect(vaultName, uri) {
        when {
            vaultName != null -> vm.loadVault(vaultName)
            uri != null -> vm.loadUri(uri)
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) vm.pausePlayback() }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    var password by remember { mutableStateOf("") }
    var confirmExport by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked -> picked?.let(vm::loadUri) }
    val exporter = rememberLauncherForActivityResult(CreateDocumentWithType()) { dest -> dest?.let(vm::exportAudio) }

    val running = op is DecryptViewModel.Op.Running
    BackHandler(enabled = running) { vm.cancel() }

    VxScaffold(title = "Déchiffrement", subtitle = "VÉRIFICATION CRYPTOGRAPHIQUE", onBack = { if (running) vm.cancel() else onBack() }) {
        val t = target
        if (t == null) {
            HudPanel(label = "Fichier chiffré") {
                Text("Sélectionnez un fichier .vxc reçu ou exporté. Il est validé avant toute demande de mot de passe.", style = MaterialTheme.typography.bodyMedium, color = Vx.Silver)
                NeonButton("Choisir un fichier .vxc", { picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), VxIcons.File)
            }
            (op as? DecryptViewModel.Op.Failed)?.let { Notice(it.message, Vx.Danger, VxIcons.Warning) }
            return@VxScaffold
        }

        HudPanel(label = "Fichier sélectionné") {
            InfoLine("Nom", t.displayName)
            InfoLine("Taille", Fmt.size(t.sizeBytes))
            t.header?.let { h ->
                InfoLine("Format", "VOXCRYPT v${h.version}")
                InfoLine("Algorithme", "AES-256-GCM segmenté")
                InfoLine("Protection", h.protection.label)
                if (h.protection == KeyProtection.PASSWORD) InfoLine("PBKDF2", "${Fmt.thousands(h.iterations)} itérations")
                InfoLine("Segments", Fmt.size(h.segmentSize.toLong()))
            }
        }

        // Verification checklist
        val success = op is DecryptViewModel.Op.Success
        val failed = op as? DecryptViewModel.Op.Failed
        val phase = (op as? DecryptViewModel.Op.Running)?.progress?.phase
        HudPanel(label = "État de vérification", accent = if (failed != null || t.headerError != null) Vx.Danger else Vx.Cyan) {
            Step("Signature et version du format", if (t.header != null) StepState.Ok else StepState.Failed)
            Step("Paramètres cryptographiques dans les limites", if (t.header != null) StepState.Ok else StepState.Failed)
            Step(
                "Dérivation de la clé",
                when {
                    success || phase == Phase.DECRYPTING -> StepState.Ok
                    phase == Phase.DERIVING_KEY -> StepState.Running
                    else -> StepState.Waiting
                },
            )
            Step(
                "Authentification AES-GCM de chaque segment",
                when {
                    success -> StepState.Ok
                    phase == Phase.DECRYPTING -> StepState.Running
                    failed != null && t.header != null -> StepState.Failed
                    else -> StepState.Waiting
                },
            )
            Step("Fin de flux authentifiée (pas de troncature)", if (success) StepState.Ok else if (failed != null && t.header != null) StepState.Failed else StepState.Waiting)
            t.headerError?.let { Notice(it, Vx.Danger, VxIcons.Warning) }
            failed?.let { if (t.header != null) Notice(it.message, Vx.Danger, VxIcons.Warning) }
        }

        when (val o = op) {
            is DecryptViewModel.Op.Running -> {
                VxProgress(o.progress.fraction)
                NeonButton("Annuler", vm::cancel, Modifier.fillMaxWidth(), VxIcons.Close, kind = ButtonKind.Danger, height = 44.dp)
            }
            is DecryptViewModel.Op.Success -> {
                HudPanel(label = "Audio authentifié", accent = Vx.Success) {
                    InfoLine("Nom d'origine", o.audio.metadata.originalName)
                    InfoLine("Créé le", Fmt.date(o.audio.metadata.createdAtMillis))
                    InfoLine("Durée", Fmt.clock(o.audio.metadata.durationMillis))
                    InfoLine("Type", o.audio.metadata.mimeType)
                    PlayerBar(player, vm::togglePlay, vm::seek)
                    Text("La copie déchiffrée est temporaire et privée. Elle est effacée en quittant cet écran.", style = MaterialTheme.typography.bodySmall, color = Vx.SilverDim)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NeonButton("Exporter l'audio", { vm.pausePlayback(); confirmExport = true }, Modifier.weight(1f), VxIcons.Export, kind = ButtonKind.Secondary, height = 44.dp)
                        NeonButton("Effacer", vm::wipeDecrypted, Modifier.weight(1f), VxIcons.Delete, kind = ButtonKind.Danger, height = 44.dp)
                    }
                }
            }
            else -> if (t.header != null) {
                HudPanel(label = "Déverrouillage") {
                    if (t.header.protection == KeyProtection.PASSWORD) {
                        PasswordField(password, { password = it }, "Mot de passe", imeAction = ImeAction.Done)
                        NeonButton(
                            "Déchiffrer et vérifier",
                            { val chars = password.toCharArray(); password = ""; vm.decrypt(chars) },
                            Modifier.fillMaxWidth(), VxIcons.Unlock, enabled = password.isNotEmpty(), height = 54.dp,
                        )
                    } else {
                        Text("Ce fichier est protégé par la clé matérielle de l'appareil qui l'a créé.", style = MaterialTheme.typography.bodySmall, color = Vx.SilverDim)
                        NeonButton("Déverrouiller avec la clé de l'appareil", { vm.decrypt(null) }, Modifier.fillMaxWidth(), VxIcons.Chip, height = 54.dp)
                    }
                }
            }
        }

        if (t.uri != null && t.header != null) {
            NeonButton("Ajouter à mes fichiers sécurisés", vm::importToVault, Modifier.fillMaxWidth(), VxIcons.Folder, kind = ButtonKind.Secondary, height = 44.dp)
        }
        notice?.let { Notice(it) }
        if (!running) {
            NeonButton("Choisir un autre fichier", { picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, height = 42.dp)
        }
    }

    if (confirmExport) {
        val meta = (op as? DecryptViewModel.Op.Success)?.audio?.metadata
        ConfirmDialog(
            title = "Exporter en clair",
            message = "La copie exportée ne bénéficiera plus de la protection cryptographique de VOXCRYPT. Toute personne ou application ayant accès à l'emplacement choisi pourra l'écouter.",
            confirmLabel = "Exporter quand même",
            danger = true,
            onConfirm = {
                confirmExport = false
                if (meta != null) exporter.launch(CreateDocumentWithType.Request(meta.originalName, meta.mimeType))
            },
            onDismiss = { confirmExport = false },
        )
    }
}

private enum class StepState { Waiting, Running, Ok, Failed }

@Composable
private fun Step(label: String, state: StepState) {
    val (color, mark) = when (state) {
        StepState.Waiting -> Vx.Muted to "·"
        StepState.Running -> Vx.Blue to "›"
        StepState.Ok -> Vx.Success to ""
        StepState.Failed -> Vx.Danger to ""
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (state) {
            StepState.Ok -> Icon(VxIcons.Check, null, tint = color, modifier = Modifier.size(16.dp))
            StepState.Failed -> Icon(VxIcons.Close, null, tint = color, modifier = Modifier.size(16.dp))
            else -> Text(mark, color = color, modifier = Modifier.width(16.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = if (state == StepState.Waiting) Vx.SilverDim else Color.White.copy(alpha = 0.9f))
    }
}
