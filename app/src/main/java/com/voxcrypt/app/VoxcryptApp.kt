package com.voxcrypt.app

import android.app.Application
import com.voxcrypt.app.audio.VoiceRecorder
import com.voxcrypt.app.crypto.VxcEngine
import com.voxcrypt.app.data.VaultRepository
import com.voxcrypt.app.security.KeystoreDeviceKeyWrapper
import com.voxcrypt.app.security.SecuritySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Manual dependency container (the app is small enough not to need a DI framework). */
class AppContainer(app: Application) {
    val engine = VxcEngine()
    val vault = VaultRepository(app.filesDir, app.cacheDir, engine)
    val settings = SecuritySettings(app)
    val deviceKey = KeystoreDeviceKeyWrapper()
    val recorder = VoiceRecorder(app, vault)
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _vaultVersion = MutableStateFlow(0)

    /** Incremented on every change of the library so screens can refresh. */
    val vaultVersion: StateFlow<Int> = _vaultVersion.asStateFlow()

    fun notifyVaultChanged() {
        _vaultVersion.value++
    }
}

class VoxcryptApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val startedAt = System.currentTimeMillis()
        container = AppContainer(this)
        // Remove leftovers of a previous session: partial writes, interrupted
        // recordings, decrypted audio and outgoing share copies.
        container.appScope.launch(Dispatchers.IO) {
            container.vault.cleanupTemporary(olderThan = startedAt)
            container.notifyVaultChanged()
        }
    }
}
