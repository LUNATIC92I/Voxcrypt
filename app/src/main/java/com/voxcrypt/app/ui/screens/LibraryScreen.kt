package com.voxcrypt.app.ui.screens

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voxcrypt.app.VoxcryptApp
import com.voxcrypt.app.audio.AudioInfo
import com.voxcrypt.app.audio.AudioPlayer
import com.voxcrypt.app.crypto.KeyProtection
import com.voxcrypt.app.crypto.VxcFormat
import com.voxcrypt.app.data.PendingRecording
import com.voxcrypt.app.data.VaultEntry
import com.voxcrypt.app.ui.components.ActionChip
import com.voxcrypt.app.ui.components.ConfirmDialog
import com.voxcrypt.app.ui.components.HudPanel
import com.voxcrypt.app.ui.components.InfoDialog
import com.voxcrypt.app.ui.components.NeonButton
import com.voxcrypt.app.ui.components.Notice
import com.voxcrypt.app.ui.components.PlayerBar
import com.voxcrypt.app.ui.components.RenameDialog
import com.voxcrypt.app.ui.components.Tag
import com.voxcrypt.app.ui.components.VxIcons
import com.voxcrypt.app.ui.components.VxScaffold
import com.voxcrypt.app.ui.theme.Vx
import com.voxcrypt.app.ui.util.CreateDocumentWithType
import com.voxcrypt.app.ui.util.Documents
import com.voxcrypt.app.ui.util.Errors
import com.voxcrypt.app.ui.util.Fmt
import com.voxcrypt.app.ui.util.Share
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as VoxcryptApp).container
    val vaultVersion = c.vaultVersion
    private val _vault = MutableStateFlow<List<VaultEntry>>(emptyList())
    val vault = _vault.asStateFlow()
    private val _pending = MutableStateFlow<List<PendingRecording>>(emptyList())
    val pending = _pending.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val player = AudioPlayer(viewModelScope)
    val playerState = player.state
    private val _playing = MutableStateFlow<String?>(null)
    val playing = _playing.asStateFlow()

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _vault.value = c.vault.listVault()
            _pending.value = c.vault.listPending()
        }
    }

    private fun run(success: String?, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                if (success != null) _message.value = success
            } catch (e: Exception) {
                _message.value = Errors.describe(e)
            }
            c.notifyVaultChanged()
        }
    }

    fun rename(e: VaultEntry, name: String) = run("Fichier renommé.") { withContext(Dispatchers.IO) { c.vault.rename(e, name) } }
    fun delete(e: VaultEntry) = run("Fichier chiffré supprimé.") { withContext(Dispatchers.IO) { c.vault.delete(e) } }

    fun renamePending(p: PendingRecording, name: String) = run("Enregistrement renommé.") {
        stopPlayback()
        withContext(Dispatchers.IO) { c.vault.renamePending(p.file, name) }
    }

    fun deletePending(p: PendingRecording) = run("Enregistrement supprimé.") {
        stopPlayback()
        withContext(Dispatchers.IO) { c.vault.deletePending(p.file) }
    }

    fun export(e: VaultEntry, uri: Uri) {
        val ctx = getApplication<Application>()
        run("Fichier chiffré exporté. Le mot de passe n'en fait pas partie.") {
            try {
                withContext(Dispatchers.IO) { Documents.openOutput(ctx, uri).use { c.vault.exportEncrypted(e, it) } }
            } catch (ex: Exception) {
                withContext(Dispatchers.IO) { Documents.deleteQuietly(ctx, uri) }
                throw ex
            }
        }
    }

    fun share(e: VaultEntry) = run(null) {
        val f = withContext(Dispatchers.IO) { c.vault.prepareShare(e) }
        Share.encryptedFile(getApplication(), f)
    }

    fun importVxc(uri: Uri) {
        val ctx = getApplication<Application>()
        run("Fichier chiffré importé. Son contenu sera authentifié au déchiffrement.") {
            withContext(Dispatchers.IO) {
                val d = AudioInfo.details(ctx, uri)
                c.vault.importVxc({ Documents.openInput(ctx, uri) }, d.displayName, d.sizeBytes)
            }
        }
    }

    fun togglePending(p: PendingRecording) {
        if (_playing.value == p.file.name) {
            player.toggle()
        } else {
            player.load(p.file)
            _playing.value = p.file.name
            player.toggle()
        }
    }

    fun seek(f: Float) = player.seekTo(f)

    fun stopPlayback() {
        player.release()
        _playing.value = null
    }

    fun dismissMessage() {
        _message.value = null
    }

    override fun onCleared() = player.release()
}

private sealed interface Dialog {
    data class Rename(val entry: VaultEntry) : Dialog
    data class Delete(val entry: VaultEntry) : Dialog
    data class Info(val entry: VaultEntry) : Dialog
    data class RenamePending(val rec: PendingRecording) : Dialog
    data class DeletePending(val rec: PendingRecording) : Dialog
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(
    onBack: () -> Unit,
    onDecrypt: (VaultEntry) -> Unit,
    onEncryptPending: (PendingRecording) -> Unit,
    onRecord: () -> Unit,
    vm: LibraryViewModel = viewModel(),
) {
    val version by vm.vaultVersion.collectAsStateWithLifecycle()
    val vault by vm.vault.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle()
    val player by vm.playerState.collectAsStateWithLifecycle()
    LaunchedEffect(version) { vm.refresh() }

    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var exporting by remember { mutableStateOf<VaultEntry?>(null) }
    val exporter = rememberLauncherForActivityResult(CreateDocumentWithType()) { uri ->
        val e = exporting
        if (uri != null && e != null) vm.export(e, uri)
        exporting = null
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importVxc) }

    VxScaffold(title = "Mes fichiers", subtitle = "BIBLIOTHÈQUE LOCALE CHIFFRÉE", onBack = onBack) {
        message?.let {
            Notice(it)
            LaunchedEffect(it) { kotlinx.coroutines.delay(6000); vm.dismissMessage() }
        }
        NeonButton("Importer un fichier .vxc", { importer.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), VxIcons.Import, height = 44.dp)

        if (pending.isNotEmpty()) {
            HudPanel(label = "Non chiffrés (${pending.size})", accent = Vx.Warning) {
                Text("Ces enregistrements sont en clair dans l'espace privé de l'application.", style = MaterialTheme.typography.bodySmall, color = Vx.SilverDim)
                pending.forEach { rec ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(rec.name, style = MaterialTheme.typography.titleMedium, color = Vx.Silver, modifier = Modifier.weight(1f))
                        Tag("En clair", Vx.Warning)
                    }
                    Text("${Fmt.date(rec.createdAt)} · ${Fmt.size(rec.sizeBytes)} · ${Fmt.clock(rec.durationMillis)}", style = MaterialTheme.typography.bodySmall, color = Vx.SilverDim)
                    if (playing == rec.file.name) PlayerBar(player, { vm.togglePending(rec) }, vm::seek)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ActionChip("Chiffrer", VxIcons.Lock, { vm.stopPlayback(); onEncryptPending(rec) })
                        ActionChip(if (playing == rec.file.name && player.playing) "Pause" else "Écouter", VxIcons.Play, { vm.togglePending(rec) }, Vx.Blue)
                        ActionChip("Renommer", VxIcons.Edit, { dialog = Dialog.RenamePending(rec) }, Vx.Blue)
                        ActionChip("Supprimer", VxIcons.Delete, { dialog = Dialog.DeletePending(rec) }, Vx.Danger)
                    }
                }
            }
        }

        if (vault.isEmpty()) {
            HudPanel(label = "Fichiers chiffrés") {
                Text("Aucun fichier chiffré pour le moment.", style = MaterialTheme.typography.bodyMedium, color = Vx.SilverDim)
                NeonButton("Enregistrer ma voix", onRecord, Modifier.fillMaxWidth(), VxIcons.Mic, height = 44.dp)
            }
        } else {
            vault.forEach { e -> VaultCard(e, onDecrypt, onExport = {
                exporting = e
                exporter.launch(CreateDocumentWithType.Request("${e.name}.${VxcFormat.FILE_EXTENSION}", "application/octet-stream"))
            }, onShare = { vm.share(e) }, onDialog = { dialog = it }) }
        }
        Notice("Le partage et l'export transmettent uniquement le fichier chiffré. Communiquez le mot de passe par un autre canal.")
    }

    when (val d = dialog) {
        is Dialog.Rename -> RenameDialog(d.entry.name, { dialog = null; vm.rename(d.entry, it) }, { dialog = null })
        is Dialog.RenamePending -> RenameDialog(d.rec.name, { dialog = null; vm.renamePending(d.rec, it) }, { dialog = null })
        is Dialog.Delete -> ConfirmDialog(
            "Supprimer définitivement",
            "« ${d.entry.name} » sera écrasé puis supprimé de cet appareil. Les copies déjà exportées ou partagées ne sont pas concernées.",
            "Supprimer", { dialog = null; vm.delete(d.entry) }, { dialog = null }, danger = true,
        )
        is Dialog.DeletePending -> ConfirmDialog(
            "Supprimer l'enregistrement",
            "« ${d.rec.name} » (non chiffré) sera écrasé puis supprimé.",
            "Supprimer", { dialog = null; vm.deletePending(d.rec) }, { dialog = null }, danger = true,
        )
        is Dialog.Info -> InfoDialog(
            "Informations techniques",
            buildList {
                add("Fichier" to "${d.entry.name}.vxc")
                add("Taille" to Fmt.size(d.entry.sizeBytes))
                add("Ajouté le" to Fmt.date(d.entry.createdAt))
                add("En-tête" to if (d.entry.headerValid) "valide" else "illisible")
                if (d.entry.headerValid) {
                    add("Format" to "VOXCRYPT v${VxcFormat.CURRENT_VERSION}")
                    add("Chiffrement" to "AES-256-GCM segmenté")
                    add("Segment" to Fmt.size(d.entry.segmentSize.toLong()))
                    add("Protection" to (d.entry.protection?.label ?: "?"))
                    if (d.entry.protection == KeyProtection.PASSWORD) add("PBKDF2" to "${Fmt.thousands(d.entry.iterations)} it.")
                }
            },
            { dialog = null },
            footer = "\nAucune clé ni aucun mot de passe n'est affiché ou stocké. L'intégrité du contenu est vérifiée au déchiffrement.",
        )
        null -> Unit
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VaultCard(
    e: VaultEntry,
    onDecrypt: (VaultEntry) -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onDialog: (Dialog) -> Unit,
) {
    HudPanel(accent = if (e.headerValid) Vx.Cyan else Vx.Danger) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(e.name, style = MaterialTheme.typography.titleMedium, color = Vx.Silver, modifier = Modifier.weight(1f))
            when (e.protection) {
                KeyProtection.PASSWORD -> Tag("Mot de passe", Vx.Cyan)
                KeyProtection.DEVICE -> Tag("Appareil", Vx.Blue)
                null -> Tag("Endommagé", Vx.Danger)
            }
        }
        Text(
            "${Fmt.date(e.createdAt)} · ${Fmt.size(e.sizeBytes)} · ${Fmt.clock(e.durationMillis)}",
            style = MaterialTheme.typography.bodySmall, color = Vx.SilverDim,
        )
        Text(
            if (e.headerValid) "ÉTAT : CHIFFRÉ · EN-TÊTE VALIDE" else "ÉTAT : EN-TÊTE ILLISIBLE",
            style = MaterialTheme.typography.labelSmall, color = if (e.headerValid) Vx.Success else Vx.Danger,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionChip("Déchiffrer", VxIcons.Unlock, { onDecrypt(e) }, enabled = e.headerValid)
            ActionChip("Exporter", VxIcons.Export, onExport, Vx.Blue)
            ActionChip("Partager", VxIcons.Share, onShare, Vx.Blue)
            ActionChip("Renommer", VxIcons.Edit, { onDialog(Dialog.Rename(e)) }, Vx.Blue)
            ActionChip("Infos", VxIcons.Info, { onDialog(Dialog.Info(e)) }, Vx.Silver)
            ActionChip("Supprimer", VxIcons.Delete, { onDialog(Dialog.Delete(e)) }, Vx.Danger)
        }
    }
}
