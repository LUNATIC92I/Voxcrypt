package com.voxcrypt.app.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voxcrypt.app.VoxcryptApp
import com.voxcrypt.app.ui.components.HudPanel
import com.voxcrypt.app.ui.components.MenuTile
import com.voxcrypt.app.ui.components.StatusLine
import com.voxcrypt.app.ui.components.VoxLogo
import com.voxcrypt.app.ui.components.VxIcons
import com.voxcrypt.app.ui.components.VxScaffold
import com.voxcrypt.app.ui.theme.Vx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as VoxcryptApp).container

    data class Status(
        val vaultCount: Int = 0,
        val pendingCount: Int = 0,
        val deviceKeyAvailable: Boolean? = null,
    )

    private val _status = MutableStateFlow(Status())
    val status = _status.asStateFlow()
    val settings = c.settings.values
    val vaultVersion = c.vaultVersion

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _status.value = Status(
                vaultCount = c.vault.listVault().size,
                pendingCount = c.vault.listPending().size,
                deviceKeyAvailable = c.deviceKey.isAvailable(),
            )
        }
    }
}

@Composable
fun HomeScreen(
    onRecord: () -> Unit,
    onEncrypt: () -> Unit,
    onDecrypt: () -> Unit,
    onLibrary: () -> Unit,
    onSecurity: () -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    val status by vm.status.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val version by vm.vaultVersion.collectAsStateWithLifecycle()
    LaunchedEffect(version) { vm.refresh() }

    VxScaffold(title = "VOXCRYPT", subtitle = "SYSTÈME DE CRYPTOGRAPHIE VOCALE") {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            VoxLogo(150.dp)
            Text("VOXCRYPT", style = MaterialTheme.typography.displaySmall, color = Vx.Silver)
            Text(
                "Your Voice. Your Privacy. Your Control.",
                style = MaterialTheme.typography.bodyMedium, color = Vx.Cyan, textAlign = TextAlign.Center,
            )
        }

        HudPanel(label = "Statut de protection") {
            StatusLine("Chiffrement local", "AES-256-GCM", Vx.Cyan)
            StatusLine("Fichiers sécurisés", status.vaultCount.toString(), Vx.Cyan)
            StatusLine(
                "Enregistrements non chiffrés",
                status.pendingCount.toString(),
                if (status.pendingCount > 0) Vx.Warning else Vx.Cyan,
            )
            StatusLine(
                "Clé matérielle de l'appareil",
                when (status.deviceKeyAvailable) {
                    null -> "..."
                    true -> "DISPONIBLE"
                    false -> "INDISPONIBLE"
                },
                if (status.deviceKeyAvailable == false) Vx.Warning else Vx.Cyan,
            )
            StatusLine("Accès réseau", "AUCUN", Vx.Cyan)
            StatusLine(
                "Captures d'écran",
                if (settings.blockScreenshots) "BLOQUÉES" else "AUTORISÉES",
                if (settings.blockScreenshots) Vx.Cyan else Vx.Warning,
            )
        }

        MenuTile(VxIcons.Mic, "Enregistrer ma voix", "Capture AAC privée, chiffrable immédiatement", onRecord)
        MenuTile(VxIcons.Lock, "Chiffrer un fichier", "Enregistrement ou fichier audio importé", onEncrypt)
        MenuTile(VxIcons.Unlock, "Déchiffrer un fichier", "Ouvrir un .vxc et vérifier son intégrité", onDecrypt, Vx.Blue)
        MenuTile(VxIcons.Folder, "Mes fichiers sécurisés", "Bibliothèque, export, partage, suppression", onLibrary, Vx.Blue)
        MenuTile(VxIcons.Shield, "Paramètres de sécurité", "Algorithmes, dérivation, protection de l'écran", onSecurity, Vx.Silver)
    }
}
