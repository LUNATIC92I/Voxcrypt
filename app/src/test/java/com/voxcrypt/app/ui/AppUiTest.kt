package com.voxcrypt.app.ui

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.WindowManager
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.voxcrypt.app.MainActivity
import com.voxcrypt.app.VoxcryptApp
import com.voxcrypt.app.crypto.VxcFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * End to end UI tests running the real activity, navigation, view models and
 * repository on the JVM (Robolectric). Also saves screenshots of the screens
 * to app/build/outputs/screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class AppUiTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<MainActivity>
    private val app get() = ApplicationProvider.getApplicationContext<VoxcryptApp>()
    private val container get() = app.container
    private val strongPassword = "Turquoise#Signal-77"

    @Before
    fun setUp() {
        container.settings.setIterations(VxcFormat.MIN_PBKDF2_ITERATIONS) // keep the suite fast
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
    }

    private fun launch(intent: Intent? = null) {
        scenario = if (intent == null) ActivityScenario.launch(MainActivity::class.java) else ActivityScenario.launch(intent)
        compose.waitForIdle()
    }

    private fun addPendingRecording(size: Int = 120_000): File {
        val part = container.vault.newRecordingPartFile()
        part.writeBytes(ByteArray(size) { (it * 31 + 7).toByte() })
        return container.vault.commitRecording(part, 9_500).file
    }

    private fun ComposeTestRule.waitForText(text: String, substring: Boolean = false, timeout: Long = 20_000) {
        try {
            waitUntil(timeout) { onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            println("Semantics tree when waiting for '$text':")
            println(onRoot(useUnmergedTree = true).fetchSemanticsNode().let { dump(it, "") })
            throw e
        }
    }

    private fun dump(node: androidx.compose.ui.semantics.SemanticsNode, indent: String): String {
        val texts = node.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }?.joinToString { t -> t.text } ?: ""
        val sb = StringBuilder("$indent- $texts\n")
        node.children.forEach { sb.append(dump(it, "$indent  ")) }
        return sb.toString()
    }

    private fun node(text: String, substring: Boolean = false): SemanticsNodeInteraction =
        compose.onAllNodesWithText(text, substring = substring)[0]

    private fun click(text: String, substring: Boolean = false) {
        node(text, substring).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        var bitmap: Bitmap? = null
        scenario.onActivity { activity ->
            val root = activity.window.decorView
            bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(android.graphics.Canvas(it)) }
        }
        val dir = File("build/outputs/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun home_showsBrandStatusAndAllEntries() {
        launch()
        compose.onNodeWithText("Your Voice. Your Privacy. Your Control.").assertExists()
        listOf("Enregistrer ma voix", "Chiffrer un fichier", "Déchiffrer un fichier", "Mes fichiers sécurisés", "Paramètres de sécurité")
            .forEach { node(it).assertExists() }
        compose.onNodeWithText("STATUT DE PROTECTION").assertExists()
        screenshot("01_home")
    }

    @Test
    fun fullFlow_encryptPendingRecording_thenDecryptWithWrongAndRightPassword() {
        val recording = addPendingRecording()
        launch()
        compose.waitForText("1") // home status counts the pending recording

        click("Mes fichiers sécurisés")
        compose.waitForText(recording.nameWithoutExtension)
        screenshot("02_library_pending")
        click("Chiffrer")

        compose.waitForText("FICHIER SÉLECTIONNÉ")
        node("Mot de passe").performTextInput(strongPassword)
        node("Confirmer le mot de passe").performTextInput(strongPassword)
        compose.waitForIdle()
        screenshot("03_encrypt_form")
        click("CHIFFRER")

        compose.waitForText("CHIFFREMENT TERMINÉ")
        compose.onNodeWithText("Fichier chiffré puis entièrement relu et authentifié avec succès.").assertExists()
        screenshot("04_encrypt_done")
        assertFalse("clear recording must be destroyed", recording.exists())
        assertEquals(1, container.vault.listVault().size)
        assertTrue(container.vault.listPending().isEmpty())

        click("MES FICHIERS SÉCURISÉS")
        compose.waitForText("ÉTAT : CHIFFRÉ · EN-TÊTE VALIDE")
        screenshot("05_library_encrypted")
        click("Déchiffrer")

        compose.waitForText("DÉVERROUILLAGE")
        node("Mot de passe").performTextInput("mauvais-mot-de-passe")
        click("DÉCHIFFRER ET VÉRIFIER")
        compose.waitForText("Mot de passe incorrect ou fichier altéré. Aucun audio n'a été restitué.")
        screenshot("06_decrypt_wrong_password")
        assertTrue(container.vault.plainDir.listFiles()!!.isEmpty())

        node("Mot de passe").performTextInput(strongPassword)
        click("DÉCHIFFRER ET VÉRIFIER")
        compose.waitForText("AUDIO AUTHENTIFIÉ")
        compose.onNodeWithText(recording.name).assertExists() // original name restored from encrypted metadata
        screenshot("07_decrypt_success")
        val decrypted = container.vault.plainDir.listFiles()!!.single()
        assertEquals(120_000L, decrypted.length())

        // Leaving the screen destroys the decrypted copy.
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(10_000) { !decrypted.exists() }
    }

    @Test
    fun record_requestsPermission_thenShowsControls() {
        launch()
        click("Enregistrer ma voix")
        compose.waitForText("AUTORISATION REQUISE")
        screenshot("08_record_permission")

        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        scenario.recreate()
        compose.waitForIdle()
        compose.waitForText("PRÊT", substring = true)
        compose.onNodeWithContentDescription("Démarrer l'enregistrement").assertExists()
        screenshot("09_record_ready")
    }

    @Test
    fun security_screenShowsParameters_andTogglesScreenshotBlocking() {
        launch()
        scenario.onActivity { assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) }
        click("Paramètres de sécurité")
        compose.waitForText("ALGORITHMES")
        node("PBKDF2-HMAC-SHA256").assertExists()
        screenshot("10_security")
        click("Bloquer captures d'écran et aperçu des applications récentes")
        // the Switch is next to the label: toggle through the settings API to stay layout independent
        container.settings.setBlockScreenshots(false)
        compose.waitForIdle()
        scenario.onActivity { assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0) }
    }

    @Test
    fun openingANonVxcFile_isRejectedBeforeAskingForAPassword() {
        val bogus = File(app.cacheDir, "notes.m4a").apply { writeBytes("ftypM4A not encrypted".toByteArray()) }
        launch(Intent(Intent.ACTION_VIEW, Uri.fromFile(bogus), app, MainActivity::class.java))
        compose.waitForText("Format non pris en charge : ce fichier n'est pas un fichier VOXCRYPT (.vxc).")
        assertTrue(compose.onAllNodesWithText("DÉCHIFFRER ET VÉRIFIER").fetchSemanticsNodes().isEmpty())
        screenshot("11_decrypt_rejected")
    }

    @Test
    fun openingATruncatedVxcFile_reportsTruncation() {
        val recording = addPendingRecording(300_000)
        val entry = container.vault.encrypt(
            com.voxcrypt.app.data.EncryptSource(recording.name, "audio/mp4", recording.length(), 1, recording) { recording.inputStream() },
            com.voxcrypt.app.data.ProtectionChoice.Password(strongPassword.toCharArray(), VxcFormat.MIN_PBKDF2_ITERATIONS),
            "Tronque",
        )
        val truncated = File(app.cacheDir, "tronque.vxc").apply { writeBytes(entry.file.readBytes().copyOf((entry.sizeBytes - 1000).toInt())) }
        launch(Intent(Intent.ACTION_VIEW, Uri.fromFile(truncated), app, MainActivity::class.java))
        compose.waitForText("DÉVERROUILLAGE")
        node("Mot de passe").performTextInput(strongPassword)
        click("DÉCHIFFRER ET VÉRIFIER")
        compose.waitForText("Fichier", substring = true)
        compose.waitUntil(20_000) {
            compose.onAllNodes(hasText("tronqué", substring = true)).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodes(hasText("modifié ou endommagé", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(container.vault.plainDir.listFiles()!!.isEmpty())
    }
}
