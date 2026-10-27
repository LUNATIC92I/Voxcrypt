package com.voxcrypt.app.data

import com.voxcrypt.app.crypto.ContentKey
import com.voxcrypt.app.crypto.DeviceKeyWrapper
import com.voxcrypt.app.crypto.KeyProtection
import com.voxcrypt.app.crypto.VxcEngine
import com.voxcrypt.app.crypto.VxcException
import com.voxcrypt.app.crypto.VxcFormat
import com.voxcrypt.app.crypto.VxcHeader
import com.voxcrypt.app.crypto.VxcMetadata
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.UUID

/** An encrypted file of the local library. */
data class VaultEntry(
    val file: File,
    val name: String,
    val createdAt: Long,
    val sizeBytes: Long,
    val durationMillis: Long,
    val protection: KeyProtection?,
    /** False when the header cannot be read (damaged or foreign file). */
    val headerValid: Boolean,
    val segmentSize: Int,
    val iterations: Int,
)

/** A recording kept in private storage, NOT encrypted yet. */
data class PendingRecording(
    val file: File,
    val name: String,
    val createdAt: Long,
    val sizeBytes: Long,
    val durationMillis: Long,
)

/** Result of a successful decryption: a private temporary audio file. */
data class DecryptedAudio(val file: File, val metadata: VxcMetadata)

/** Something to encrypt. [open] may be called once. */
class EncryptSource(
    val originalName: String,
    val mimeType: String,
    /** Size in bytes, -1 when unknown. */
    val sizeBytes: Long,
    val durationMillis: Long,
    /** Pending recording to destroy once the encrypted copy is verified. */
    val pendingFile: File? = null,
    val open: () -> InputStream,
)

sealed interface ProtectionChoice {
    class Password(val password: CharArray, val iterations: Int) : ProtectionChoice
    class Device(val wrapper: DeviceKeyWrapper) : ProtectionChoice
}

sealed interface UnlockChoice {
    class Password(val password: CharArray) : UnlockChoice
    class Device(val wrapper: DeviceKeyWrapper) : UnlockChoice
}

enum class Phase { DERIVING_KEY, ENCRYPTING, VERIFYING, DECRYPTING }

data class Progress(val phase: Phase, val fraction: Float?)

class InsufficientStorageException(val neededBytes: Long, val availableBytes: Long) :
    IOException("Espace de stockage insuffisant.")

/**
 * Local storage of VOXCRYPT. Pure JVM (java.io.File) so it is unit tested
 * without a device; Android specific Uri handling lives in the UI layer.
 *
 * Directories (all private to the app, excluded from backups):
 *  - files/vault    encrypted .vxc files (+ ".index" with non sensitive info)
 *  - files/pending  recordings not encrypted yet
 *  - cache/plain    decrypted audio, deleted after use and at every start
 *  - cache/outgoing copies of ENCRYPTED files handed to the share sheet
 *
 * Writes always go to a hidden ".part" file in the destination directory and
 * are renamed only after success, so a crash or cancellation never leaves a
 * file that looks valid.
 */
class VaultRepository(
    filesRoot: File,
    cacheRoot: File,
    private val engine: VxcEngine = VxcEngine(),
    /** Safety margin kept free on top of the expected output size. */
    private val storageMargin: Long = 2L * 1024 * 1024,
    private val usableSpace: (File) -> Long = { it.usableSpace },
) {
    val vaultDir = File(filesRoot, "vault")
    val pendingDir = File(filesRoot, "pending")
    val plainDir = File(cacheRoot, "plain")
    val outgoingDir = File(cacheRoot, "outgoing")
    private val indexFile = File(vaultDir, ".index")
    private val lock = Any()

    init {
        listOf(vaultDir, pendingDir, plainDir, outgoingDir).forEach { it.mkdirs() }
    }

    // ---------------------------------------------------------------- cleanup

    /**
     * Removes every temporary artefact: partial writes, interrupted recordings,
     * decrypted audio, outgoing share copies. Returns the number of files removed.
     * Only files last modified before [olderThan] are touched, so a cleanup run
     * in the background at startup never removes a file of an operation that
     * started meanwhile.
     */
    fun cleanupTemporary(olderThan: Long = Long.MAX_VALUE): Int {
        var count = 0
        val stale = { f: File -> f.lastModified() < olderThan }
        listOf(vaultDir, pendingDir).forEach { dir ->
            dir.listFiles()?.filter { it.name.startsWith(PART_PREFIX) && stale(it) }?.forEach { if (secureDelete(it)) count++ }
        }
        listOf(plainDir, outgoingDir).forEach { dir ->
            dir.listFiles()?.filter(stale)?.forEach { if (secureDelete(it)) count++ }
        }
        return count
    }

    /** Deletes every decrypted audio file (manual cleanup from the security screen). */
    fun clearDecrypted() {
        plainDir.listFiles()?.forEach { secureDelete(it) }
    }

    /** Deletes one decrypted copy (when its screen is left or the user wipes it). */
    fun discardDecrypted(audio: DecryptedAudio) {
        requireInside(audio.file, plainDir)
        secureDelete(audio.file)
    }

    // ---------------------------------------------------------------- listing

    fun listVault(): List<VaultEntry> {
        val index = loadIndex()
        return vaultDir.listFiles { f -> f.isFile && f.name.endsWith(".${VxcFormat.FILE_EXTENSION}") && !f.name.startsWith(".") }
            .orEmpty()
            .map { toEntry(it, index) }
            .sortedByDescending { it.createdAt }
    }

    fun findVault(name: String): VaultEntry? {
        val f = File(vaultDir, name)
        return if (f.isFile && f.parentFile == vaultDir) toEntry(f, loadIndex()) else null
    }

    private fun toEntry(file: File, index: Properties): VaultEntry {
        val header = try {
            file.inputStream().buffered().use { VxcHeader.read(it) }
        } catch (e: Exception) {
            null
        }
        val info = index.getProperty(file.name)?.split(';')
        return VaultEntry(
            file = file,
            name = file.name.removeSuffix(".${VxcFormat.FILE_EXTENSION}"),
            createdAt = info?.getOrNull(0)?.toLongOrNull() ?: file.lastModified(),
            sizeBytes = file.length(),
            durationMillis = info?.getOrNull(1)?.toLongOrNull() ?: -1,
            protection = header?.protection,
            headerValid = header != null,
            segmentSize = header?.segmentSize ?: 0,
            iterations = header?.iterations ?: 0,
        )
    }

    fun listPending(): List<PendingRecording> {
        val index = loadIndex()
        return pendingDir.listFiles { f -> f.isFile && !f.name.startsWith(".") }
            .orEmpty()
            .map {
                PendingRecording(
                    it, it.nameWithoutExtension, it.lastModified(), it.length(),
                    index.getProperty(PENDING_KEY + it.name)?.toLongOrNull() ?: -1,
                )
            }
            .sortedByDescending { it.createdAt }
    }

    /** Hidden file the recorder writes to; [commitRecording] publishes it. */
    fun newRecordingPartFile(): File = File(pendingDir, "${PART_PREFIX}${UUID.randomUUID()}.m4a")

    fun commitRecording(part: File, durationMillis: Long): PendingRecording {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date())
        val target = uniqueFile(pendingDir, "Voix_$stamp", "m4a")
        moveOrThrow(part, target)
        updateIndex { it.setProperty(PENDING_KEY + target.name, durationMillis.toString()) }
        return PendingRecording(target, target.nameWithoutExtension, target.lastModified(), target.length(), durationMillis)
    }

    fun renamePending(file: File, newName: String): File {
        requireInside(file, pendingDir)
        val target = uniqueFile(pendingDir, sanitizeName(newName), file.extension.ifEmpty { "m4a" }, except = file)
        if (target == file) return file
        moveOrThrow(file, target)
        updateIndex { p ->
            p.getProperty(PENDING_KEY + file.name)?.let { p.setProperty(PENDING_KEY + target.name, it) }
            p.remove(PENDING_KEY + file.name)
        }
        return target
    }

    fun deletePending(file: File): Boolean {
        requireInside(file, pendingDir)
        updateIndex { it.remove(PENDING_KEY + file.name) }
        return secureDelete(file)
    }

    // ------------------------------------------------------------- encryption

    /**
     * Encrypts [source] into the vault. Steps: storage check, key preparation,
     * streaming encryption into a hidden part file, full authentication of the
     * written file with the same key, publication by rename. Only then is the
     * pending clear recording destroyed. On any failure (cancellation included)
     * the partial file is removed and the source is left untouched.
     */
    fun encrypt(
        source: EncryptSource,
        protection: ProtectionChoice,
        displayName: String,
        onProgress: (Progress) -> Unit = {},
    ): VaultEntry {
        if (source.sizeBytes == 0L) throw VxcException.InvalidArgument("Le fichier audio est vide.")
        val expected = if (source.sizeBytes > 0) expectedCiphertextSize(source.sizeBytes) else 0
        ensureSpace(vaultDir, expected)

        val part = File(vaultDir, "$PART_PREFIX${UUID.randomUUID()}")
        onProgress(Progress(Phase.DERIVING_KEY, null))
        val key: ContentKey = when (protection) {
            is ProtectionChoice.Password -> engine.preparePassword(protection.password, protection.iterations)
            is ProtectionChoice.Device -> engine.prepareDevice(protection.wrapper)
        }
        try {
            key.use {
                val metadata = VxcMetadata(
                    originalName = source.originalName,
                    mimeType = source.mimeType,
                    createdAtMillis = System.currentTimeMillis(),
                    durationMillis = source.durationMillis,
                    audioLength = source.sizeBytes,
                )
                val written = source.open().use { input ->
                    FileOutputStream(part).use { fos ->
                        val out = fos.buffered(IO_BUFFER)
                        val n = engine.encrypt(key, metadata, input, out) { done ->
                            onProgress(Progress(Phase.ENCRYPTING, fraction(done, source.sizeBytes)))
                        }
                        out.flush()
                        fos.fd.sync()
                        n
                    }
                }
                if (written == 0L) throw VxcException.InvalidArgument("Le fichier audio est vide.")

                // Read back and authenticate everything that was written.
                val body = part.length() - key.header.length
                part.inputStream().buffered(IO_BUFFER).use { input ->
                    val header = VxcHeader.read(input)
                    if (!header.encoded.contentEquals(key.header.encoded)) throw VxcException.Tampered()
                    engine.verify(key, input) { done -> onProgress(Progress(Phase.VERIFYING, fraction(done, body))) }
                }
            }
            val target = uniqueFile(vaultDir, sanitizeName(displayName), VxcFormat.FILE_EXTENSION)
            moveOrThrow(part, target)
            updateIndex { it.setProperty(target.name, "${System.currentTimeMillis()};${source.durationMillis}") }
            source.pendingFile?.let { deletePending(it) }
            return toEntry(target, loadIndex())
        } catch (t: Throwable) {
            secureDelete(part)
            throw t
        }
    }

    // ------------------------------------------------------------- decryption

    /** Reads and validates only the header (cheap, no key derivation). */
    fun inspect(open: () -> InputStream): VxcHeader = open().buffered().use { VxcHeader.read(it) }

    /**
     * Decrypts into a private temporary file. The audio file is only returned
     * after the complete stream has been authenticated; on any error the
     * partial output is destroyed and nothing is returned.
     */
    fun decrypt(
        open: () -> InputStream,
        totalSize: Long,
        unlock: UnlockChoice,
        onProgress: (Progress) -> Unit = {},
    ): DecryptedAudio {
        val part = File(plainDir, "$PART_PREFIX${UUID.randomUUID()}")
        try {
            open().buffered(IO_BUFFER).use { input ->
                val header = VxcHeader.read(input)
                if (totalSize > 0) ensureSpace(plainDir, totalSize)
                onProgress(Progress(Phase.DERIVING_KEY, null))
                val key = when (unlock) {
                    is UnlockChoice.Password -> engine.openPassword(header, unlock.password)
                    is UnlockChoice.Device -> engine.openDevice(header, unlock.wrapper)
                }
                val body = if (totalSize > 0) totalSize - header.length else -1
                val metadata = key.use {
                    FileOutputStream(part).use { fos ->
                        val out = fos.buffered(IO_BUFFER)
                        val meta = engine.decrypt(key, input, out) { done ->
                            onProgress(Progress(Phase.DECRYPTING, fraction(done, body)))
                        }
                        out.flush()
                        meta
                    }
                }
                // Authenticated: publish under a neutral name.
                val target = File(plainDir, "audio_${UUID.randomUUID()}.${extensionFor(metadata)}")
                moveOrThrow(part, target)
                return DecryptedAudio(target, metadata)
            }
        } catch (t: Throwable) {
            secureDelete(part)
            throw t
        }
    }

    // ------------------------------------------------------ files management

    fun rename(entry: VaultEntry, newName: String): VaultEntry {
        requireInside(entry.file, vaultDir)
        val target = uniqueFile(vaultDir, sanitizeName(newName), VxcFormat.FILE_EXTENSION, except = entry.file)
        if (target != entry.file) {
            moveOrThrow(entry.file, target)
            updateIndex { p ->
                p.getProperty(entry.file.name)?.let { p.setProperty(target.name, it) }
                p.remove(entry.file.name)
            }
        }
        return toEntry(target, loadIndex())
    }

    /** Overwrites then deletes the file (see limits of flash storage in the docs). */
    fun delete(entry: VaultEntry): Boolean {
        requireInside(entry.file, vaultDir)
        updateIndex { it.remove(entry.file.name) }
        return secureDelete(entry.file)
    }

    /** Copies the encrypted file as is (the password is never part of it). */
    fun exportEncrypted(entry: VaultEntry, output: OutputStream) {
        requireInside(entry.file, vaultDir)
        entry.file.inputStream().use { it.copyTo(output, IO_BUFFER) }
        output.flush()
    }

    /** Copy of the encrypted file placed in the FileProvider folder for sharing. */
    fun prepareShare(entry: VaultEntry): File {
        requireInside(entry.file, vaultDir)
        outgoingDir.listFiles()?.forEach { it.delete() }
        val target = File(outgoingDir, entry.file.name)
        entry.file.copyTo(target, overwrite = true)
        return target
    }

    fun exportDecrypted(audio: DecryptedAudio, output: OutputStream) {
        requireInside(audio.file, plainDir)
        audio.file.inputStream().use { it.copyTo(output, IO_BUFFER) }
        output.flush()
    }

    /**
     * Copies a .vxc file received from elsewhere into the vault. The header is
     * validated first; the content can only be authenticated with the password.
     */
    fun importVxc(open: () -> InputStream, suggestedName: String, sizeBytes: Long): VaultEntry {
        if (sizeBytes > 0) ensureSpace(vaultDir, sizeBytes)
        val part = File(vaultDir, "$PART_PREFIX${UUID.randomUUID()}")
        try {
            open().buffered(IO_BUFFER).use { input ->
                val header = VxcHeader.read(input)
                FileOutputStream(part).use { out ->
                    out.write(header.encoded)
                    input.copyTo(out, IO_BUFFER)
                    out.fd.sync()
                }
            }
            val base = sanitizeName(suggestedName.removeSuffix(".${VxcFormat.FILE_EXTENSION}"))
            val target = uniqueFile(vaultDir, base, VxcFormat.FILE_EXTENSION)
            moveOrThrow(part, target)
            updateIndex { it.setProperty(target.name, "${System.currentTimeMillis()};-1") }
            return toEntry(target, loadIndex())
        } catch (t: Throwable) {
            secureDelete(part)
            throw t
        }
    }

    // ---------------------------------------------------------------- helpers

    fun ensureSpace(dir: File, needed: Long) {
        val available = usableSpace(dir)
        if (needed + storageMargin > available) throw InsufficientStorageException(needed + storageMargin, available)
    }

    private fun expectedCiphertextSize(plain: Long): Long {
        val seg = VxcFormat.DEFAULT_SEGMENT_SIZE.toLong()
        val segments = (plain + VxcFormat.MAX_METADATA_BYTES) / seg + 1
        return plain + VxcFormat.MAX_METADATA_BYTES + segments * VxcFormat.GCM_TAG_BYTES + VxcFormat.MAX_HEADER_BYTES
    }

    private fun fraction(done: Long, total: Long): Float? =
        if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null

    private fun extensionFor(meta: VxcMetadata): String {
        val fromName = meta.originalName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (fromName.matches(Regex("[a-z0-9]{1,5}"))) return fromName
        return when (meta.mimeType) {
            "audio/mpeg" -> "mp3"
            "audio/ogg" -> "ogg"
            "audio/wav", "audio/x-wav" -> "wav"
            "audio/aac" -> "aac"
            "audio/flac" -> "flac"
            else -> "m4a"
        }
    }

    private fun requireInside(file: File, dir: File) {
        if (file.canonicalFile.parentFile != dir.canonicalFile) {
            throw IllegalArgumentException("Fichier hors de l'espace VOXCRYPT.")
        }
    }

    private fun uniqueFile(dir: File, base: String, ext: String, except: File? = null): File {
        var candidate = File(dir, "$base.$ext")
        var n = 2
        while (candidate.exists() && candidate != except) {
            candidate = File(dir, "$base ($n).$ext")
            n++
        }
        return candidate
    }

    private fun moveOrThrow(from: File, to: File) {
        if (!from.renameTo(to)) throw IOException("Impossible de finaliser le fichier.")
    }

    private fun loadIndex(): Properties = synchronized(lock) {
        Properties().apply { if (indexFile.exists()) indexFile.inputStream().use { load(it) } }
    }

    private fun updateIndex(block: (Properties) -> Unit) = synchronized(lock) {
        val p = loadIndex()
        block(p)
        val tmp = File(vaultDir, "${PART_PREFIX}index")
        tmp.outputStream().use { p.store(it, null) }
        if (!tmp.renameTo(indexFile)) {
            indexFile.delete()
            tmp.renameTo(indexFile)
        }
    }

    companion object {
        const val PART_PREFIX = ".part-"
        private const val PENDING_KEY = "pending/"
        private const val IO_BUFFER = 64 * 1024
        const val MAX_NAME_LENGTH = 64

        /** Keeps a readable, file system safe name; never empty. */
        fun sanitizeName(raw: String): String {
            val cleaned = raw.trim()
                .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
                .trimStart('.')
                .take(MAX_NAME_LENGTH)
                .trim()
            return cleaned.ifEmpty { "Enregistrement" }
        }

        /**
         * Overwrites the content with zeros before deleting. On flash storage
         * (wear levelling, journaling) this does not guarantee physical erasure,
         * see docs/SECURITY.md. Encrypted files do not need it to stay protected.
         */
        fun secureDelete(file: File): Boolean {
            if (!file.exists()) return false
            try {
                if (file.isFile) {
                    RandomAccessFile(file, "rw").use { raf ->
                        val zeros = ByteArray(64 * 1024)
                        var remaining = raf.length()
                        raf.seek(0)
                        while (remaining > 0) {
                            val n = minOf(remaining, zeros.size.toLong()).toInt()
                            raf.write(zeros, 0, n)
                            remaining -= n
                        }
                        raf.fd.sync()
                    }
                }
            } catch (e: IOException) {
                // Fall through to a plain delete.
            }
            return file.delete()
        }
    }
}
