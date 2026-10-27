package com.voxcrypt.app.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.voxcrypt.app.ui.util.vxViewModel
import com.voxcrypt.app.BuildConfig
import com.voxcrypt.app.VoxcryptApp
import com.voxcrypt.app.crypto.KeyDerivation
import com.voxcrypt.app.crypto.VxcFormat
import com.voxcrypt.app.security.SecuritySettings
import com.voxcrypt.app.ui.components.ActionChip
import com.voxcrypt.app.ui.components.ButtonKind
import com.voxcrypt.app.ui.components.HudPanel
import com.voxcrypt.app.ui.components.InfoLine
import com.voxcrypt.app.ui.components.NeonButton
import com.voxcrypt.app.ui.components.Notice
import com.voxcrypt.app.ui.components.StatusLine
import com.voxcrypt.app.ui.components.VxIcons
import com.voxcrypt.app.ui.components.VxScaffold
import com.voxcrypt.app.ui.theme.Vx
import com.voxcrypt.app.ui.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SecurityViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as VoxcryptApp).container
    val settings = c.settings.values
    private val _benchmarking = MutableStateFlow(false)
    val benchmarking = _benchmarking.asStateFlow()
    private val _deviceKey = MutableStateFlow<Boolean?>(null)
    val deviceKey = _deviceKey.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    fun load() {
        viewModelScope.launch(Dispatchers.IO) { _deviceKey.value = c.deviceKey.isAvailable() }
    }

    fun setIterations(v: Int) = c.settings.setIterations(v)
    fun setBlockScreenshots(v: Boolean) = c.settings.setBlockScreenshots(v)

    /** Measures one PBKDF2 derivation with the selected iteration count on this device. */
    fun benchmark() {
        if (_benchmarking.value) return
        _benchmarking.value = true
        val it = settings.value.iterations
        viewModelScope.launch {
            val ms = withContext(Dispatchers.Default) { KeyDerivation.benchmark(it) }
            c.settings.recordBenchmark(it, ms)
            _benchmarking.value = false
        }
    }

    fun cleanup() {
        viewModelScope.launch {
            val n = withContext(Dispatchers.IO) { c.vault.cleanupTemporary() }
            _message.value = "$n fichier(s) temporaire(s) effacé(s)."
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SecurityScreen(onBack: () -> Unit, vm: SecurityViewModel = vxViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val bench by vm.benchmarking.collectAsStateWithLifecycle()
    val deviceKey by vm.deviceKey.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }

    VxScaffold(title = "Sécurité", subtitle = "PARAMÈTRES ET CONCEPTION CRYPTOGRAPHIQUE", onBack = onBack) {
        HudPanel(label = "Algorithmes") {
            InfoLine("Chiffrement", "AES-256-GCM")
            InfoLine("Construction", "Flux segmenté authentifié")
            InfoLine("Segment", "${VxcFormat.DEFAULT_SEGMENT_SIZE / 1024} Kio + tag 128 bits")
            InfoLine("Nonce", "96 bits : préfixe aléatoire 56 + compteur 32 + fin 8")
            InfoLine("Données associées", "En-tête complet")
            InfoLine("Dérivation", "PBKDF2-HMAC-SHA256")
            InfoLine("Sel", "${VxcFormat.SALT_BYTES * 8} bits, unique par fichier")
            InfoLine("Format", ".vxc version ${VxcFormat.CURRENT_VERSION}")
            InfoLine("Aléa", "SecureRandom (système)")
            InfoLine("Application", "v${BuildConfig.VERSION_NAME}")
        }

        HudPanel(label = "Dérivation de clé (mot de passe)") {
            Text("Nombre d'itérations PBKDF2 pour les nouveaux fichiers. Plus il est élevé, plus une attaque par force brute est lente, mais plus l'ouverture prend de temps. La valeur est enregistrée dans chaque fichier : les anciens fichiers restent lisibles.", style = MaterialTheme.typography.bodySmall, color = Vx.SilverDim)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SecuritySettings.ITERATION_CHOICES.forEach { v ->
                    ActionChip(
                        (if (v == s.iterations) "● " else "") + Fmt.thousands(v),
                        VxIcons.Key, { vm.setIterations(v) },
                        if (v == s.iterations) Vx.Cyan else Vx.SilverDim,
                    )
                }
            }
            InfoLine("Valeur actuelle", "${Fmt.thousands(s.iterations)} itérations")
            if (s.lastBenchmarkMillis >= 0) {
                InfoLine("Mesure sur cet appareil", "${Fmt.thousands(s.lastBenchmarkIterations)} it. = ${s.lastBenchmarkMillis} ms")
            }
            NeonButton(if (bench) "Mesure en cours..." else "Mesurer sur cet appareil", vm::benchmark, Modifier.fillMaxWidth(), VxIcons.Chip, enabled = !bench, kind = ButtonKind.Secondary, height = 44.dp)
            Text("Cible recommandée : entre 0,5 et 1 seconde par dérivation sur l'appareil le plus lent qui devra ouvrir vos fichiers.", style = MaterialTheme.typography.bodySmall, color = Vx.SilverDim)
        }

        HudPanel(label = "Modes de protection") {
            Text("MOT DE PASSE", style = MaterialTheme.typography.labelLarge, color = Vx.Cyan)
            Text("Clé dérivée du mot de passe. Le fichier se transfère sur n'importe quel appareil et s'ouvre avec le bon mot de passe. Un mot de passe oublié rend le fichier irrécupérable.", style = MaterialTheme.typography.bodySmall, color = Vx.Silver)
            Text("CLÉ DE L'APPAREIL", style = MaterialTheme.typography.labelLarge, color = Vx.Blue)
            Text("Clé aléatoire par fichier, protégée par une clé Android Keystore non exportable. Pratique pour un usage local, mais le fichier ne s'ouvre que sur cet appareil et devient illisible après réinstallation ou réinitialisation.", style = MaterialTheme.typography.bodySmall, color = Vx.Silver)
            StatusLine("Android Keystore", when (deviceKey) { null -> "..."; true -> "DISPONIBLE"; false -> "INDISPONIBLE" }, if (deviceKey == false) Vx.Warning else Vx.Cyan)
        }

        HudPanel(label = "Protection de l'écran") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Bloquer captures d'écran et aperçu des applications récentes", style = MaterialTheme.typography.bodyMedium, color = Vx.Silver, modifier = Modifier.weight(1f))
                Switch(
                    checked = s.blockScreenshots, onCheckedChange = vm::setBlockScreenshots,
                    colors = SwitchDefaults.colors(checkedThumbColor = Vx.Black, checkedTrackColor = Vx.Cyan, uncheckedTrackColor = Vx.Line),
                )
            }
            Text("Cette mesure empêche les captures logicielles standard. Elle ne protège pas contre un appareil photo externe ni contre un système compromis.", style = MaterialTheme.typography.bodySmall, color = Vx.SilverDim)
        }

        HudPanel(label = "Garanties de l'application") {
            StatusLine("Permission Internet", "ABSENTE", Vx.Cyan)
            StatusLine("Compte, télémétrie, publicité", "AUCUN", Vx.Cyan)
            StatusLine("Sauvegarde cloud Android", "DÉSACTIVÉE", Vx.Cyan)
            StatusLine("Stockage des mots de passe", "JAMAIS", Vx.Cyan)
            StatusLine("Journaux contenant des secrets", "AUCUN", Vx.Cyan)
        }

        HudPanel(label = "Recommandations", accent = Vx.Warning) {
            listOf(
                "Utilisez une phrase de passe longue et unique (4 mots ou plus).",
                "Transmettez le mot de passe par un canal différent de celui du fichier.",
                "Chiffrez vos enregistrements dès la fin de la capture.",
                "N'exportez l'audio déchiffré qu'en cas de nécessité : la copie n'est plus protégée.",
                "Activez le verrouillage de l'écran et le chiffrement de l'appareil Android.",
                "Conservez une copie des fichiers importants protégés par mot de passe : un fichier lié à l'appareil disparaît avec lui.",
            ).forEach { Text("›  $it", style = MaterialTheme.typography.bodySmall, color = Vx.Silver) }
        }

        NeonButton("Effacer les fichiers temporaires", vm::cleanup, Modifier.fillMaxWidth(), VxIcons.Broom, kind = ButtonKind.Secondary, height = 44.dp)
        message?.let { Notice(it) }
    }
}
