package com.voxcrypt.app.ui.screens

import android.app.Application
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.voxcrypt.app.ui.util.vxViewModel
import com.voxcrypt.app.VoxcryptApp
import com.voxcrypt.app.audio.AudioInfo
import com.voxcrypt.app.crypto.PasswordPolicy
import com.voxcrypt.app.crypto.VxcFormat
import com.voxcrypt.app.data.EncryptSource
import com.voxcrypt.app.data.PendingRecording
import com.voxcrypt.app.data.Phase
import com.voxcrypt.app.data.Progress
import com.voxcrypt.app.data.ProtectionChoice
import com.voxcrypt.app.data.VaultEntry
import com.voxcrypt.app.ui.components.ActionChip
import com.voxcrypt.app.ui.components.ButtonKind
import com.voxcrypt.app.ui.components.ChoiceRow
import com.voxcrypt.app.ui.components.HudPanel
import com.voxcrypt.app.ui.components.InfoLine
import com.voxcrypt.app.ui.components.NeonButton
import com.voxcrypt.app.ui.components.Notice
import com.voxcrypt.app.ui.components.PasswordField
import com.voxcrypt.app.ui.components.StrengthMeter
import com.voxcrypt.app.ui.components.VxIcons
import com.voxcrypt.app.ui.components.VxProgress
import com.voxcrypt.app.ui.components.VxScaffold
import com.voxcrypt.app.ui.components.VxTextField
import com.voxcrypt.app.ui.theme.Vx
import com.voxcrypt.app.ui.util.CreateDocumentWithType
import com.voxcrypt.app.ui.util.Documents
import com.voxcrypt.app.ui.util.Errors
import com.voxcrypt.app.ui.util.Fmt
import com.voxcrypt.app.ui.util.Share
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class EncryptViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as VoxcryptApp).container
    val settings = c.settings.values

    data class Source(
        val name: String,
        val sizeBytes: Long,
        val durationMillis: Long,
        val mimeType: String,
        val pendingFile: File? = null,
        val uri: Uri? = null,
        val problem: String? = null,
    )

    sealed interface Op {
        data object Idle : Op
        data class Running(val progress: Progress) : Op
        data class Done(val entry: VaultEntry, val fromPending: Boolean) : Op
        data class Failed(val message: String) : Op
    }

    private val _source = MutableStateFlow<Source?>(null)
    val source = _source.asStateFlow()
    private val _op = MutableStateFlow<Op>(Op.Idle)
    val op = _op.asStateFlow()
    private val _pending = MutableStateFlow<List<PendingRecording>>(emptyList())
    val pending = _pending.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    val notice = _notice.asStateFlow()
    private var job: Job? = null

    fun refreshPending() {
        viewModelScope.launch(Dispatchers.IO) { _pending.value = c.vault.listPending() }
    }

    fun selectPending(fileName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val rec = c.vault.listPending().firstOrNull { it.file.name == fileName }
            _source.value = rec?.let {
                Source(it.file.name, it.sizeBytes, it.durationMillis, "audio/mp4", pendingFile = it.file,
                    problem = if (it.sizeBytes == 0L) "Fichier vide : rien à chiffrer." else null)
            }
            if (rec == null) _op.value = Op.Failed("Enregistrement introuvable.")
        }
    }

    fun selectUri(uri: Uri) {
        _op.value = Op.Idle
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val d = AudioInfo.details(ctx, uri)
            val readable = AudioInfo.looksLikeAudio(ctx, uri)
            val problem = when {
                d.sizeBytes == 0L -> "Fichier vide : rien à chiffrer."
                !readable -> "Ce fichier n'est pas un audio lisible ou il est corrompu. Choisissez un autre fichier."
                else -> null
            }
            _source.value = Source(
                name = d.displayName,
                sizeBytes = d.sizeBytes,
                durationMillis = if (readable) AudioInfo.durationOf(ctx, uri) else -1,
                mimeType = if (d.mimeType.startsWith("audio/")) d.mimeType else "audio/mp4",
                uri = uri,
                problem = problem,
            )
        }
    }

    fun encrypt(password: CharArray?, deviceMode: Boolean, outputName: String) {
        val src = _source.value ?: return
        if (_op.value is Op.Running) return
        val ctx = getApplication<Application>()
        val iterations = c.settings.values.value.iterations
        job = viewModelScope.launch(Dispatchers.Default) {
            val self = coroutineContext
            _op.value = Op.Running(Progress(Phase.DERIVING_KEY, null))
            try {
                val source = EncryptSource(
                    originalName = src.name,
                    mimeType = src.mimeType,
                    sizeBytes = src.sizeBytes,
                    durationMillis = src.durationMillis,
                    pendingFile = src.pendingFile,
                ) {
                    src.pendingFile?.inputStream() ?: Documents.openInput(ctx, src.uri!!)
                }
                val protection = if (deviceMode) ProtectionChoice.Device(c.deviceKey)
                else ProtectionChoice.Password(password!!, iterations)
                val entry = c.vault.encrypt(source, protection, outputName) { p ->
                    self.ensureActive()
                    _op.value = Op.Running(p)
                }
                _op.value = Op.Done(entry, src.pendingFile != null)
                c.notifyVaultChanged()
            } catch (e: CancellationException) {
                _op.value = Op.Failed("Opération annulée. Aucun fichier partiel n'a été conservé.")
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

    fun export(entry: VaultEntry, uri: Uri) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            _notice.value = try {
                withContext(Dispatchers.IO) { Documents.openOutput(ctx, uri).use { c.vault.exportEncrypted(entry, it) } }
                "Fichier chiffré exporté. Le mot de passe n'en fait pas partie."
            } catch (e: Exception) {
                withContext(Dispatchers.IO) { Documents.deleteQuietly(ctx, uri) }
                Errors.describe(e)
            }
        }
    }

    fun share(entry: VaultEntry) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            try {
                val f = withContext(Dispatchers.IO) { c.vault.prepareShare(entry) }
                Share.encryptedFile(ctx, f)
            } catch (e: Exception) {
                _notice.value = Errors.describe(e)
            }
        }
    }

    fun reset() {
        _source.value = null
        _op.value = Op.Idle
        _notice.value = null
        refreshPending()
    }
}

@Composable
fun EncryptScreen(
    pendingName: String?,
    uri: Uri?,
    onBack: () -> Unit,
    onLibrary: () -> Unit,
    vm: EncryptViewModel = vxViewModel(),
) {
    val source by vm.source.collectAsStateWithLifecycle()
    val op by vm.op.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()

    LaunchedEffect(pendingName, uri) {
        when {
            pendingName != null -> vm.selectPending(pendingName)
            uri != null -> vm.selectUri(uri)
            else -> vm.refreshPending()
        }
    }

    // Passwords are kept in plain remember (NOT rememberSaveable) so they are
    // never written to the saved instance state.
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var mode by remember { mutableIntStateOf(0) }
    var outputName by remember(source?.name) { mutableStateOf(source?.name?.substringBeforeLast('.') ?: "") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked -> picked?.let(vm::selectUri) }
    val exporter = rememberLauncherForActivityResult(CreateDocumentWithType()) { target ->
        val done = op as? EncryptViewModel.Op.Done
        if (target != null && done != null) vm.export(done.entry, target)
    }

    val running = op is EncryptViewModel.Op.Running
    BackHandler(enabled = running) { vm.cancel() }

    VxScaffold(title = "Chiffrement", subtitle = "AES-256-GCM · FORMAT .VXC v${VxcFormat.CURRENT_VERSION}", onBack = { if (running) vm.cancel() else onBack() }) {
        val src = source
        if (src == null) {
            HudPanel(label = "Source audio") {
                Text("Choisissez un enregistrement non chiffré ou importez un fichier audio de l'appareil.", style = MaterialTheme.typography.bodyMedium, color = Vx.Silver)
                NeonButton("Importer un fichier audio", { picker.launch(arrayOf("audio/*")) }, Modifier.fillMaxWidth(), VxIcons.Import)
            }
            if (pending.isNotEmpty()) {
                HudPanel(label = "Enregistrements non chiffrés", accent = Vx.Warning) {
                    pending.forEach { rec ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Text("${rec.name}\n${Fmt.clock(rec.durationMillis)} · ${Fmt.size(rec.sizeBytes)}", style = MaterialTheme.typography.bodySmall, color = Vx.Silver, modifier = Modifier.weight(1f))
                            ActionChip("Choisir", VxIcons.Lock, { vm.selectPending(rec.file.name) })
                        }
                    }
                }
            }
            return@VxScaffold
        }

        HudPanel(label = "Fichier sélectionné") {
            InfoLine("Nom", src.name)
            InfoLine("Taille", Fmt.size(src.sizeBytes))
            InfoLine("Durée", Fmt.clock(src.durationMillis))
            InfoLine("Type", src.mimeType)
            InfoLine("Origine", if (src.pendingFile != null) "Enregistrement VOXCRYPT" else "Fichier importé")
            src.problem?.let { Notice(it, Vx.Danger, VxIcons.Warning) }
        }

        when (val o = op) {
            is EncryptViewModel.Op.Done -> {
                HudPanel(label = "Chiffrement terminé", accent = Vx.Success) {
                    Notice("Fichier chiffré puis entièrement relu et authentifié avec succès.", Vx.Success, VxIcons.Check)
                    InfoLine("Fichier", "${o.entry.name}.vxc")
                    InfoLine("Taille", Fmt.size(o.entry.sizeBytes))
                    InfoLine("Protection", o.entry.protection?.label ?: "")
                    if (o.fromPending) {
                        Text("La copie audio en clair a été détruite.", style = MaterialTheme.typography.bodySmall, color = Vx.SilverDim)
                    } else {
                        Notice("Le fichier d'origine n'a pas été modifié par VOXCRYPT. Supprimez-le vous-même si vous ne souhaitez plus en conserver une copie en clair.", Vx.Warning, VxIcons.Warning)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NeonButton("Exporter", { exporter.launch(CreateDocumentWithType.Request("${o.entry.name}.vxc", "application/octet-stream")) }, Modifier.weight(1f), VxIcons.Export, kind = ButtonKind.Secondary, height = 44.dp)
                        NeonButton("Partager", { vm.share(o.entry) }, Modifier.weight(1f), VxIcons.Share, kind = ButtonKind.Secondary, height = 44.dp)
                    }
                    notice?.let { Notice(it) }
                    NeonButton("Mes fichiers sécurisés", onLibrary, Modifier.fillMaxWidth(), VxIcons.Folder)
                }
                return@VxScaffold
            }
            is EncryptViewModel.Op.Running -> {
                HudPanel(label = "Opération en cours", accent = Vx.Blue) {
                    val p = o.progress
                    Text(
                        when (p.phase) {
                            Phase.DERIVING_KEY -> if (mode == 0) "Dérivation de la clé (PBKDF2, ${Fmt.thousands(settings.iterations)} itérations)..." else "Création de la clé de fichier..."
                            Phase.ENCRYPTING -> "Chiffrement des segments audio..."
                            Phase.VERIFYING -> "Vérification de l'intégrité du fichier écrit..."
                            Phase.DECRYPTING -> ""
                        },
                        style = MaterialTheme.typography.bodyMedium, color = Vx.Silver,
                    )
                    VxProgress(p.fraction)
                    Text(p.fraction?.let { "${(it * 100).toInt()} %" } ?: "", style = MaterialTheme.typography.labelLarge, color = Vx.Cyan)
                    NeonButton("Annuler", vm::cancel, Modifier.fillMaxWidth(), VxIcons.Close, kind = ButtonKind.Danger, height = 44.dp)
                }
                return@VxScaffold
            }
            is EncryptViewModel.Op.Failed -> Notice(o.message, Vx.Danger, VxIcons.Warning)
            EncryptViewModel.Op.Idle -> Unit
        }

        HudPanel(label = "Méthode de protection") {
            ChoiceRow(listOf("Mot de passe", "Clé appareil"), mode, { mode = it })
            if (mode == 0) {
                Text(
                    "Fichier transférable : il pourra être déchiffré sur n'importe quel appareil VOXCRYPT avec ce mot de passe. Sans le mot de passe, personne ne peut le récupérer, pas même vous.",
                    style = MaterialTheme.typography.bodySmall, color = Vx.SilverDim,
                )
                PasswordField(password, { password = it }, "Mot de passe")
                StrengthMeter(PasswordPolicy.evaluate(password.toCharArray()))
                PasswordField(
                    confirm, { confirm = it }, "Confirmer le mot de passe",
                    isError = confirm.isNotEmpty() && confirm != password, imeAction = ImeAction.Done,
                )
                if (confirm.isNotEmpty() && confirm != password) {
                    Text("Les mots de passe ne correspondent pas.", style = MaterialTheme.typography.bodySmall, color = Vx.Danger)
                }
                Text("Minimum ${PasswordPolicy.MIN_LENGTH} caractères. Une phrase de passe longue est recommandée.", style = MaterialTheme.typography.bodySmall, color = Vx.SilverDim)
            } else {
                Notice(
                    "Protection liée à cet appareil : la clé est générée et conservée par Android Keystore, elle ne peut pas être exportée. Le fichier ne pourra PAS être déchiffré sur un autre appareil, ni après réinstallation de l'application ou réinitialisation du téléphone. Utilisez le mot de passe pour un fichier transférable.",
                    Vx.Warning, VxIcons.Chip,
                )
            }
        }

        HudPanel(label = "Fichier de sortie") {
            VxTextField(outputName, { outputName = it }, "Nom du fichier chiffré (.vxc)")
            Text("Ce nom est visible. Le nom d'origine et les métadonnées sont chiffrés dans le fichier.", style = MaterialTheme.typography.bodySmall, color = Vx.SilverDim)
        }

        val passwordOk = mode == 1 || (PasswordPolicy.isAcceptable(password.toCharArray()) && password == confirm)
        NeonButton(
            "Chiffrer",
            {
                val chars = if (mode == 0) password.toCharArray() else null
                password = ""
                confirm = ""
                vm.encrypt(chars, mode == 1, outputName)
            },
            Modifier.fillMaxWidth(),
            VxIcons.Lock,
            enabled = src.problem == null && passwordOk && outputName.isNotBlank(),
            height = 56.dp,
        )
        NeonButton("Changer de fichier", { password = ""; confirm = ""; vm.reset() }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, height = 42.dp)
    }
}
