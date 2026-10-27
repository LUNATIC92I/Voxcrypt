package com.voxcrypt.app.data

import com.voxcrypt.app.crypto.InMemoryDeviceKeyWrapper
import com.voxcrypt.app.crypto.KeyProtection
import com.voxcrypt.app.crypto.PASSWORD
import com.voxcrypt.app.crypto.TEST_ITERATIONS
import com.voxcrypt.app.crypto.VxcException
import com.voxcrypt.app.crypto.fakeAudio
import com.voxcrypt.app.crypto.indexOf
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

class VaultRepositoryTest {
    private lateinit var root: File
    private lateinit var repo: VaultRepository

    @Before
    fun setUp() {
        root = Files.createTempDirectory("vxc-repo").toFile()
        repo = VaultRepository(File(root, "files"), File(root, "cache"))
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun pendingRecording(audio: ByteArray, duration: Long = 4_000): PendingRecording {
        val part = repo.newRecordingPartFile()
        part.writeBytes(audio)
        return repo.commitRecording(part, duration)
    }

    private fun sourceFor(rec: PendingRecording) = EncryptSource(
        rec.file.name, "audio/mp4", rec.sizeBytes, rec.durationMillis, rec.file,
    ) { rec.file.inputStream() }

    private fun password() = ProtectionChoice.Password(PASSWORD.copyOf(), TEST_ITERATIONS)

    private fun decryptEntry(entry: VaultEntry, unlock: UnlockChoice = UnlockChoice.Password(PASSWORD.copyOf())) =
        repo.decrypt({ entry.file.inputStream() }, entry.sizeBytes, unlock)

    private fun allFiles(): List<File> = root.walkTopDown().filter { it.isFile }.toList()

    @Test
    fun recording_encrypt_decrypt_fullFlow() {
        val audio = fakeAudio(200_000)
        val rec = pendingRecording(audio)
        assertEquals(1, repo.listPending().size)
        assertEquals(4_000, repo.listPending().first().durationMillis)

        val phases = mutableSetOf<Phase>()
        val entry = repo.encrypt(sourceFor(rec), password(), "Note de test") { phases += it.phase }

        assertEquals(setOf(Phase.DERIVING_KEY, Phase.ENCRYPTING, Phase.VERIFYING), phases)
        assertEquals("Note de test", entry.name)
        assertEquals(KeyProtection.PASSWORD, entry.protection)
        assertEquals(4_000, entry.durationMillis)
        assertTrue(entry.headerValid)
        // Clear recording destroyed only after verified encryption.
        assertFalse(rec.file.exists())
        assertTrue(repo.listPending().isEmpty())
        // No clear copy of the audio anywhere in app storage.
        for (f in allFiles()) assertEquals(f.path, -1, f.readBytes().indexOf(audio.copyOfRange(5000, 5032)))

        val decrypted = decryptEntry(entry)
        assertArrayEquals(audio, decrypted.file.readBytes())
        assertEquals(rec.file.name, decrypted.metadata.originalName)
        assertTrue(decrypted.file.parentFile == repo.plainDir)
    }

    @Test
    fun wrongPassword_leavesNoDecryptedFile() {
        val entry = repo.encrypt(sourceFor(pendingRecording(fakeAudio(30_000))), password(), "x")
        try {
            decryptEntry(entry, UnlockChoice.Password("mauvais-mot-de-passe".toCharArray()))
            fail()
        } catch (e: VxcException.AuthenticationFailed) {
            // expected
        }
        assertTrue(repo.plainDir.listFiles()!!.isEmpty())
    }

    @Test
    fun tamperedFile_leavesNoDecryptedFile() {
        val entry = repo.encrypt(sourceFor(pendingRecording(fakeAudio(300_000))), password(), "x")
        val bytes = entry.file.readBytes()
        bytes[bytes.size - 5000] = (bytes[bytes.size - 5000].toInt() xor 1).toByte()
        entry.file.writeBytes(bytes)
        try {
            decryptEntry(entry)
            fail()
        } catch (e: VxcException.Tampered) {
            // expected: first segments were fine (correct password), a later one is not
        }
        assertTrue(repo.plainDir.listFiles()!!.isEmpty())
    }

    @Test
    fun failureDuringEncryption_cleansPartialFile_andKeepsSource() {
        val rec = pendingRecording(fakeAudio(500_000))
        val failing = EncryptSource(rec.file.name, "audio/mp4", rec.sizeBytes, -1, rec.file) {
            object : InputStream() {
                val inner = rec.file.inputStream()
                var read = 0
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (read > 200_000) throw IOException("lecture interrompue")
                    return inner.read(b, off, len).also { if (it > 0) read += it }
                }
                override fun close() = inner.close()
            }
        }
        try {
            repo.encrypt(failing, password(), "x")
            fail()
        } catch (e: IOException) {
            // expected
        }
        assertTrue(rec.file.exists())
        assertTrue(repo.listVault().isEmpty())
        assertTrue(repo.vaultDir.listFiles()!!.none { it.name.startsWith(VaultRepository.PART_PREFIX) && it.name != ".part-index" })
    }

    @Test
    fun cancellationDuringEncryption_cleansPartialFile() {
        val rec = pendingRecording(fakeAudio(600_000))
        class Cancelled : RuntimeException()
        try {
            repo.encrypt(sourceFor(rec), password(), "x") { p ->
                if (p.phase == Phase.ENCRYPTING && (p.fraction ?: 0f) > 0.3f) throw Cancelled()
            }
            fail()
        } catch (e: Cancelled) {
            // expected
        }
        assertTrue(rec.file.exists())
        assertTrue(repo.listVault().isEmpty())
        assertEquals(listOf(".index").filter { File(repo.vaultDir, it).exists() }, repo.vaultDir.list()!!.toList())
    }

    @Test
    fun cancellationDuringDecryption_leavesNothing() {
        val entry = repo.encrypt(sourceFor(pendingRecording(fakeAudio(600_000))), password(), "x")
        class Cancelled : RuntimeException()
        try {
            repo.decrypt({ entry.file.inputStream() }, entry.sizeBytes, UnlockChoice.Password(PASSWORD.copyOf())) { p ->
                if (p.phase == Phase.DECRYPTING && (p.fraction ?: 0f) > 0.5f) throw Cancelled()
            }
            fail()
        } catch (e: Cancelled) {
            // expected
        }
        assertTrue(repo.plainDir.listFiles()!!.isEmpty())
    }

    @Test
    fun emptyAudio_isRefused() {
        val rec = pendingRecording(ByteArray(0))
        try {
            repo.encrypt(sourceFor(rec), password(), "x")
            fail()
        } catch (e: VxcException.InvalidArgument) {
            // expected
        }
        // Unknown size source that turns out empty.
        try {
            repo.encrypt(EncryptSource("a.m4a", "audio/mp4", -1, -1) { ByteArrayInputStream(ByteArray(0)) }, password(), "x")
            fail()
        } catch (e: VxcException.InvalidArgument) {
            // expected
        }
        assertTrue(repo.listVault().isEmpty())
    }

    @Test
    fun insufficientStorage_isReportedBeforeWriting() {
        val tiny = VaultRepository(File(root, "f2"), File(root, "c2"), usableSpace = { 1_000_000L })
        val part = tiny.newRecordingPartFile().apply { writeBytes(fakeAudio(5_000_000)) }
        val rec = tiny.commitRecording(part, 1)
        try {
            tiny.encrypt(EncryptSource(rec.file.name, "audio/mp4", rec.sizeBytes, 1, rec.file) { rec.file.inputStream() }, password(), "x")
            fail()
        } catch (e: InsufficientStorageException) {
            assertTrue(e.availableBytes == 1_000_000L)
        }
        assertTrue(rec.file.exists())
    }

    @Test
    fun importFromUnknownSizeStream_andExport_roundTrip() {
        val audio = fakeAudio(50_000)
        val entry = repo.encrypt(
            EncryptSource("import.mp3", "audio/mpeg", -1, -1) { ByteArrayInputStream(audio) }, password(), "Import",
        )
        val exported = ByteArrayOutputStream().also { repo.exportEncrypted(entry, it) }.toByteArray()
        assertArrayEquals(entry.file.readBytes(), exported)
        // Exported file contains neither password nor audio.
        assertEquals(-1, exported.indexOf(String(PASSWORD).toByteArray()))
        assertEquals(-1, exported.indexOf(audio.copyOfRange(100, 140)))

        // "Other device": a fresh repository imports and decrypts it with the password.
        val other = VaultRepository(File(root, "other-files"), File(root, "other-cache"))
        val imported = other.importVxc({ ByteArrayInputStream(exported) }, "Import.vxc", exported.size.toLong())
        assertEquals("Import", imported.name)
        val dec = other.decrypt({ imported.file.inputStream() }, imported.sizeBytes, UnlockChoice.Password(PASSWORD.copyOf()))
        assertArrayEquals(audio, dec.file.readBytes())
        assertTrue(dec.file.name.endsWith(".mp3"))
    }

    @Test
    fun importRejectsNonVxcFiles() {
        try {
            repo.importVxc({ ByteArrayInputStream("ID3 not a vxc".toByteArray()) }, "x.vxc", 13)
            fail()
        } catch (e: VxcException.NotVxcFile) {
            // expected
        }
        assertTrue(repo.listVault().isEmpty())
    }

    @Test
    fun rename_keepsIndex_andAvoidsCollisions() {
        val a = repo.encrypt(sourceFor(pendingRecording(fakeAudio(1000), 777)), password(), "A")
        val b = repo.encrypt(sourceFor(pendingRecording(fakeAudio(1000))), password(), "B")
        val renamed = repo.rename(a, "../../evil/B")
        assertEquals("_.._evil_B", renamed.name)
        assertEquals(777, renamed.durationMillis)
        val clash = repo.rename(renamed, "B")
        assertEquals("B (2)", clash.name)
        assertEquals(setOf("B", "B (2)"), repo.listVault().map { it.name }.toSet())
        assertNotNull(repo.findVault("B.vxc"))
        assertTrue(b.file.exists())
    }

    @Test
    fun delete_removesFileAndIndex() {
        val e = repo.encrypt(sourceFor(pendingRecording(fakeAudio(1000))), password(), "A")
        assertTrue(repo.delete(e))
        assertFalse(e.file.exists())
        assertTrue(repo.listVault().isEmpty())
    }

    @Test
    fun pendingRename_andDelete() {
        val rec = pendingRecording(fakeAudio(1000), 1234)
        val renamed = repo.renamePending(rec.file, "Mon memo")
        assertEquals("Mon memo.m4a", renamed.name)
        assertEquals(1234, repo.listPending().single().durationMillis)
        assertTrue(repo.deletePending(renamed))
        assertTrue(repo.listPending().isEmpty())
    }

    @Test
    fun cleanupTemporary_removesPartsDecryptedAndOutgoing() {
        val e = repo.encrypt(sourceFor(pendingRecording(fakeAudio(1000))), password(), "A")
        decryptEntry(e)
        repo.prepareShare(e)
        File(repo.vaultDir, ".part-crash").writeText("x")
        File(repo.pendingDir, ".part-interrupted.m4a").writeText("x")
        val removed = repo.cleanupTemporary()
        assertEquals(4, removed)
        assertTrue(repo.plainDir.listFiles()!!.isEmpty())
        assertTrue(repo.outgoingDir.listFiles()!!.isEmpty())
        assertEquals(1, repo.listVault().size)
    }

    @Test
    fun deviceProtection_flow() {
        val wrapper = InMemoryDeviceKeyWrapper()
        val audio = fakeAudio(70_000)
        val e = repo.encrypt(sourceFor(pendingRecording(audio)), ProtectionChoice.Device(wrapper), "Local")
        assertEquals(KeyProtection.DEVICE, e.protection)
        assertArrayEquals(audio, decryptEntry(e, UnlockChoice.Device(wrapper)).file.readBytes())
        try {
            decryptEntry(e, UnlockChoice.Device(InMemoryDeviceKeyWrapper()))
            fail()
        } catch (ex: VxcException.DeviceKeyUnavailable) {
            // expected
        }
    }

    @Test
    fun damagedHeader_isListedAsInvalid() {
        File(repo.vaultDir, "broken.vxc").writeText("garbage")
        val entry = repo.listVault().single()
        assertFalse(entry.headerValid)
        assertEquals(null, entry.protection)
    }

    @Test
    fun operationsOutsideTheVault_areRefused() {
        val outside = File(root, "outside.vxc").apply { writeText("x") }
        val fake = VaultEntry(outside, "outside", 0, 1, -1, null, false, 0, 0)
        try {
            repo.delete(fake)
            fail()
        } catch (e: IllegalArgumentException) {
            // expected
        }
        assertTrue(outside.exists())
    }

    @Test
    fun sanitizeName() {
        assertEquals("Enregistrement", VaultRepository.sanitizeName("   "))
        assertEquals("a_b", VaultRepository.sanitizeName("a/b"))
        assertEquals("hidden", VaultRepository.sanitizeName("..hidden"))
        assertEquals(64, VaultRepository.sanitizeName("x".repeat(200)).length)
    }

    @Test
    fun secureDelete_overwritesBeforeDeleting() {
        val f = File(root, "s").apply { writeBytes(ByteArray(100_000) { 1 }) }
        assertTrue(VaultRepository.secureDelete(f))
        assertFalse(f.exists())
    }
}
