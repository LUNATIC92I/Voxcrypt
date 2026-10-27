package com.voxcrypt.app.crypto

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Key material for exactly one file. Holds the 256 bit content key and the
 * header it belongs to. Close it as soon as the operation ends: the key bytes
 * are wiped (best effort, the JVM may have made internal copies).
 *
 * A key prepared for encryption can encrypt only once. This guarantees that a
 * (key, nonce prefix) pair is never used for two different streams.
 */
class ContentKey internal constructor(
    val header: VxcHeader,
    keyBytes: ByteArray,
    private val forEncryption: Boolean,
) : AutoCloseable {
    private var bytes: ByteArray? = keyBytes
    private var encryptionDone = false

    internal fun secretKey(): SecretKeySpec =
        SecretKeySpec(bytes ?: throw IllegalStateException("Clé déjà effacée."), "AES")

    internal fun claimForEncryption() {
        if (!forEncryption) throw VxcException.InvalidArgument("Cette clé ne peut servir qu'au déchiffrement.")
        if (encryptionDone) throw VxcException.InvalidArgument("Une clé de fichier ne peut chiffrer qu'une seule fois.")
        encryptionDone = true
    }

    override fun close() {
        bytes?.fill(0)
        bytes = null
    }

    override fun toString(): String = "ContentKey(protection=${header.protection}, key=<redacted>)"
}

/**
 * VOXCRYPT encryption engine.
 *
 * Construction: segmented AES-256-GCM (online authenticated encryption, same
 * design family as the "STREAM" construction of Hoang, Reyhanitabar, Rogaway
 * and Vizár, used by Google Tink). The plaintext is cut in segments of
 * `segmentSize` bytes. Segment `i` is encrypted with the nonce
 * `noncePrefix(7) || i (u32, big endian) || lastFlag (1 byte)`
 * and the whole header as associated data. Consequences:
 *  - a nonce is never repeated for a given key (unique counter per segment,
 *    unique key per file),
 *  - reordering or removing segments changes the expected nonce: detected,
 *  - truncation is detected because the final segment carries lastFlag = 1,
 *  - any header modification is detected because the header is the AAD.
 *
 * Memory use is bounded by two segments whatever the audio size.
 */
class VxcEngine(private val random: SecureRandom = SecureRandom()) {

    /** Creates a fresh password protected key: new salt, new nonce prefix. */
    fun preparePassword(
        password: CharArray,
        iterations: Int = VxcFormat.DEFAULT_PBKDF2_ITERATIONS,
        segmentSize: Int = VxcFormat.DEFAULT_SEGMENT_SIZE,
    ): ContentKey {
        val salt = randomBytes(VxcFormat.SALT_BYTES)
        val prefix = randomBytes(VxcFormat.NONCE_PREFIX_BYTES)
        val header = VxcHeader.forPassword(iterations, salt, prefix, segmentSize)
        return ContentKey(header, KeyDerivation.pbkdf2(password, salt, iterations), forEncryption = true)
    }

    /** Creates a fresh random data key protected by the device key. */
    fun prepareDevice(
        wrapper: DeviceKeyWrapper,
        segmentSize: Int = VxcFormat.DEFAULT_SEGMENT_SIZE,
    ): ContentKey {
        val prefix = randomBytes(VxcFormat.NONCE_PREFIX_BYTES)
        val dataKey = randomBytes(VxcFormat.KEY_SIZE_BYTES)
        try {
            val wrapped = wrapper.wrap(dataKey, deviceAad(prefix))
            val header = VxcHeader.forDevice(wrapped, prefix, segmentSize)
            return ContentKey(header, dataKey.copyOf(), forEncryption = true)
        } finally {
            dataKey.fill(0)
        }
    }

    /** Derives the key of an existing password protected file. */
    fun openPassword(header: VxcHeader, password: CharArray): ContentKey {
        if (header.protection != KeyProtection.PASSWORD) {
            throw VxcException.InvalidArgument("Ce fichier n'est pas protégé par mot de passe.")
        }
        return ContentKey(header, KeyDerivation.pbkdf2(password, header.salt, header.iterations), forEncryption = false)
    }

    /** Unwraps the key of an existing device protected file. */
    fun openDevice(header: VxcHeader, wrapper: DeviceKeyWrapper): ContentKey {
        if (header.protection != KeyProtection.DEVICE) {
            throw VxcException.InvalidArgument("Ce fichier n'est pas protégé par la clé de l'appareil.")
        }
        val key = wrapper.unwrap(header.wrappedKey, deviceAad(header.noncePrefix))
        if (key.size != VxcFormat.KEY_SIZE_BYTES) {
            key.fill(0)
            throw VxcException.DeviceKeyUnavailable()
        }
        return ContentKey(header, key, forEncryption = false)
    }

    /**
     * Writes a complete ".vxc" file to [output]: header, then encrypted segments.
     * [onProgress] receives the number of audio bytes consumed; it may throw
     * (for example a CancellationException) to abort the operation.
     * Returns the number of audio bytes encrypted.
     */
    fun encrypt(
        key: ContentKey,
        metadata: VxcMetadata,
        audio: InputStream,
        output: OutputStream,
        onProgress: (Long) -> Unit = {},
    ): Long {
        key.claimForEncryption()
        val header = key.header
        val meta = metadata.encode()
        val metaBlock = ByteBuffer.allocate(4 + meta.size).putInt(meta.size).put(meta).array()
        val countingAudio = CountingInputStream(audio)
        val plain = SequenceInputStream(ByteArrayInputStream(metaBlock), countingAudio)

        output.write(header.encoded)

        val seg = header.segmentSize
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val secret = key.secretKey()
        val aad = header.encoded
        val prefix = header.noncePrefix
        var current = ByteArray(seg)
        var next = ByteArray(seg)
        val out = ByteArray(seg + VxcFormat.GCM_TAG_BYTES)
        var currentLen = readFully(plain, current)
        var counter = 0L
        while (true) {
            val nextLen = if (currentLen == seg) readFully(plain, next) else 0
            val last = nextLen == 0
            if (counter > VxcFormat.MAX_SEGMENTS) throw VxcException.InvalidArgument("Fichier trop volumineux.")
            cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(TAG_BITS, nonce(prefix, counter, last)))
            cipher.updateAAD(aad)
            val n = cipher.doFinal(current, 0, currentLen, out, 0)
            output.write(out, 0, n)
            onProgress(countingAudio.count)
            if (last) break
            val tmp = current; current = next; next = tmp
            currentLen = nextLen
            counter++
        }
        output.flush()
        current.fill(0); next.fill(0)
        if (metadata.audioLength >= 0 && metadata.audioLength != countingAudio.count) {
            // The declared length is authenticated: a mismatch would make the file unreadable.
            throw VxcException.InvalidArgument("La taille de l'audio a changé pendant le chiffrement.")
        }
        return countingAudio.count
    }

    /**
     * Decrypts the segments that follow the header (the stream must be
     * positioned right after it, see [VxcHeader.read]) and writes the audio to
     * [audioOutput].
     *
     * IMPORTANT: segments are written as soon as each one is authenticated,
     * but the file as a whole is only valid when this function returns
     * normally. Callers must write to a temporary location and publish the
     * result only after success (the repository layer does this).
     *
     * [onProgress] receives the number of encrypted bytes consumed after the header.
     */
    fun decrypt(
        key: ContentKey,
        input: InputStream,
        audioOutput: OutputStream,
        onProgress: (Long) -> Unit = {},
    ): VxcMetadata {
        val header = key.header
        val seg = header.segmentSize
        val segCt = seg + VxcFormat.GCM_TAG_BYTES
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val secret = key.secretKey()
        val aad = header.encoded
        val prefix = header.noncePrefix
        val splitter = MetadataSplitter(audioOutput)
        var current = ByteArray(segCt)
        var next = ByteArray(segCt)
        val plain = ByteArray(segCt)
        var consumed = 0L
        var currentLen = readFully(input, current)
        var counter = 0L
        while (true) {
            if (currentLen < VxcFormat.GCM_TAG_BYTES) throw VxcException.Truncated()
            if (counter > VxcFormat.MAX_SEGMENTS) throw VxcException.Tampered()
            val nextLen = if (currentLen == segCt) readFully(input, next) else 0
            val last = nextLen == 0
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(TAG_BITS, nonce(prefix, counter, last)))
            cipher.updateAAD(aad)
            val n = try {
                cipher.doFinal(current, 0, currentLen, plain, 0)
            } catch (e: AEADBadTagException) {
                plain.fill(0)
                throw when {
                    counter == 0L -> VxcException.AuthenticationFailed()
                    last && currentLen == segCt -> VxcException.Truncated()
                    else -> VxcException.Tampered(e)
                }
            }
            splitter.write(plain, 0, n)
            consumed += currentLen
            onProgress(consumed)
            if (last) break
            val tmp = current; current = next; next = tmp
            currentLen = nextLen
            counter++
        }
        plain.fill(0); current.fill(0); next.fill(0)
        audioOutput.flush()
        return splitter.finish()
    }

    /** Authenticates a whole file without keeping the plaintext. */
    fun verify(key: ContentKey, input: InputStream, onProgress: (Long) -> Unit = {}): VxcMetadata =
        decrypt(key, input, NullOutputStream, onProgress)

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = VxcFormat.GCM_TAG_BYTES * 8
        private val DEVICE_AAD_LABEL = "VOXCRYPT-DEVICE-KEY-v1".toByteArray(Charsets.US_ASCII)

        internal fun deviceAad(noncePrefix: ByteArray): ByteArray = DEVICE_AAD_LABEL + noncePrefix

        internal fun nonce(prefix: ByteArray, counter: Long, last: Boolean): ByteArray =
            ByteBuffer.allocate(VxcFormat.GCM_NONCE_BYTES)
                .put(prefix)
                .putInt(counter.toInt())
                .put(if (last) 1.toByte() else 0.toByte())
                .array()

        internal fun readFully(input: InputStream, buffer: ByteArray): Int {
            var off = 0
            while (off < buffer.size) {
                val n = input.read(buffer, off, buffer.size - off)
                if (n < 0) break
                off += n
            }
            return off
        }
    }

    private object NullOutputStream : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    private class CountingInputStream(private val source: InputStream) : InputStream() {
        var count = 0L
            private set

        override fun read(): Int = source.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            source.read(b, off, len).also { if (it > 0) count += it }
    }

    /** Separates the length prefixed metadata block from the audio bytes. */
    private class MetadataSplitter(private val audio: OutputStream) {
        private val lengthBytes = ByteArray(4)
        private var lengthFilled = 0
        private var metaBytes: ByteArray? = null
        private var metaFilled = 0
        private var audioCount = 0L

        fun write(b: ByteArray, off: Int, len: Int) {
            var pos = off
            val end = off + len
            while (pos < end && lengthFilled < 4) {
                lengthBytes[lengthFilled++] = b[pos++]
                if (lengthFilled == 4) {
                    val metaLen = ByteBuffer.wrap(lengthBytes).int
                    if (metaLen <= 0 || metaLen > VxcFormat.MAX_METADATA_BYTES) {
                        throw VxcException.InvalidMetadata("longueur invalide")
                    }
                    metaBytes = ByteArray(metaLen)
                }
            }
            val meta = metaBytes ?: return
            if (metaFilled < meta.size && pos < end) {
                val take = minOf(meta.size - metaFilled, end - pos)
                System.arraycopy(b, pos, meta, metaFilled, take)
                metaFilled += take
                pos += take
            }
            if (pos < end) {
                audio.write(b, pos, end - pos)
                audioCount += end - pos
            }
        }

        fun finish(): VxcMetadata {
            val meta = metaBytes
            if (meta == null || metaFilled < meta.size) throw VxcException.InvalidMetadata("bloc absent")
            val decoded = VxcMetadata.decode(meta)
            if (decoded.audioLength >= 0 && decoded.audioLength != audioCount) {
                throw VxcException.InvalidMetadata("taille audio incohérente")
            }
            return decoded
        }
    }
}
