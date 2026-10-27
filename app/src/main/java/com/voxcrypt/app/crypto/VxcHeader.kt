package com.voxcrypt.app.crypto

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.BufferUnderflowException

/**
 * Plaintext header of a ".vxc" file, format version 1.
 *
 * Layout (big endian):
 * ```
 * off  size  field
 * 0    8     magic "VOXCRYPT"
 * 8    1     format version (1)
 * 9    2     header length in bytes (whole header, magic included)
 * 11   1     cipher suite (1 = AES-256-GCM segmented)
 * 12   1     key protection (1 = password PBKDF2, 2 = device Keystore)
 * 13   1     flags (reserved, must be 0)
 * 14   4     segment size (plaintext bytes per segment)
 * 18   7     nonce prefix (random, per file)
 * 25   ...   protection parameters
 *            PASSWORD: u32 iterations, u8 salt length, salt
 *            DEVICE:   u16 wrapped key length, wrapped key
 * ```
 * The complete encoded header is used as associated data of every encrypted
 * segment, so any modification of it is detected during decryption.
 */
class VxcHeader private constructor(
    val version: Int,
    val cipherSuite: Int,
    val protection: KeyProtection,
    val segmentSize: Int,
    noncePrefix: ByteArray,
    val iterations: Int,
    salt: ByteArray,
    wrappedKey: ByteArray,
    encoded: ByteArray,
) {
    private val noncePrefixBytes = noncePrefix.copyOf()
    private val saltBytes = salt.copyOf()
    private val wrappedKeyBytes = wrappedKey.copyOf()
    private val encodedBytes = encoded.copyOf()

    val noncePrefix: ByteArray get() = noncePrefixBytes.copyOf()
    val salt: ByteArray get() = saltBytes.copyOf()
    val wrappedKey: ByteArray get() = wrappedKeyBytes.copyOf()

    /** Exact bytes of the header as written in the file (associated data). */
    val encoded: ByteArray get() = encodedBytes.copyOf()
    val length: Int get() = encodedBytes.size

    companion object {
        private const val FIXED_PART = 25

        fun forPassword(
            iterations: Int,
            salt: ByteArray,
            noncePrefix: ByteArray,
            segmentSize: Int = VxcFormat.DEFAULT_SEGMENT_SIZE,
        ): VxcHeader = build(KeyProtection.PASSWORD, segmentSize, noncePrefix, iterations, salt, ByteArray(0))

        fun forDevice(
            wrappedKey: ByteArray,
            noncePrefix: ByteArray,
            segmentSize: Int = VxcFormat.DEFAULT_SEGMENT_SIZE,
        ): VxcHeader = build(KeyProtection.DEVICE, segmentSize, noncePrefix, 0, ByteArray(0), wrappedKey)

        private fun build(
            protection: KeyProtection,
            segmentSize: Int,
            noncePrefix: ByteArray,
            iterations: Int,
            salt: ByteArray,
            wrappedKey: ByteArray,
        ): VxcHeader {
            validate(protection, segmentSize, noncePrefix, iterations, salt, wrappedKey)
            val body = ByteArrayOutputStream()
            DataOutputStream(body).use { out ->
                out.writeByte(VxcFormat.CIPHER_AES256_GCM_SEGMENTED)
                out.writeByte(protection.id)
                out.writeByte(0) // flags
                out.writeInt(segmentSize)
                out.write(noncePrefix)
                when (protection) {
                    KeyProtection.PASSWORD -> {
                        out.writeInt(iterations)
                        out.writeByte(salt.size)
                        out.write(salt)
                    }
                    KeyProtection.DEVICE -> {
                        out.writeShort(wrappedKey.size)
                        out.write(wrappedKey)
                    }
                }
            }
            val bodyBytes = body.toByteArray()
            val total = VxcFormat.HEADER_PREFIX_BYTES + bodyBytes.size
            val encoded = ByteBuffer.allocate(total)
                .put(VxcFormat.MAGIC)
                .put(VxcFormat.CURRENT_VERSION.toByte())
                .putShort(total.toShort())
                .put(bodyBytes)
                .array()
            return VxcHeader(
                VxcFormat.CURRENT_VERSION, VxcFormat.CIPHER_AES256_GCM_SEGMENTED, protection,
                segmentSize, noncePrefix, iterations, salt, wrappedKey, encoded,
            )
        }

        private fun validate(
            protection: KeyProtection,
            segmentSize: Int,
            noncePrefix: ByteArray,
            iterations: Int,
            salt: ByteArray,
            wrappedKey: ByteArray,
        ) {
            fun fail(reason: String): Nothing = throw VxcException.InvalidHeader(reason)
            if (segmentSize !in VxcFormat.MIN_SEGMENT_SIZE..VxcFormat.MAX_SEGMENT_SIZE) {
                fail("taille de segment hors limites")
            }
            if (noncePrefix.size != VxcFormat.NONCE_PREFIX_BYTES) fail("préfixe de nonce invalide")
            when (protection) {
                KeyProtection.PASSWORD -> {
                    if (iterations !in VxcFormat.MIN_PBKDF2_ITERATIONS..VxcFormat.MAX_PBKDF2_ITERATIONS) {
                        fail("nombre d'itérations PBKDF2 hors limites")
                    }
                    if (salt.size !in VxcFormat.MIN_SALT_BYTES..VxcFormat.MAX_SALT_BYTES) {
                        fail("taille de sel hors limites")
                    }
                    if (wrappedKey.isNotEmpty()) fail("clé enveloppée inattendue")
                }
                KeyProtection.DEVICE -> {
                    if (wrappedKey.size !in VxcFormat.MIN_WRAPPED_KEY_BYTES..VxcFormat.MAX_WRAPPED_KEY_BYTES) {
                        fail("clé enveloppée de taille invalide")
                    }
                    if (iterations != 0 || salt.isNotEmpty()) fail("paramètres de dérivation inattendus")
                }
            }
        }

        /**
         * Reads and validates a header from [input]. Consumes exactly the header
         * bytes, leaving the stream positioned on the first encrypted segment.
         * Nothing expensive happens here: all limits are enforced first.
         */
        fun read(input: InputStream): VxcHeader {
            val data = DataInputStream(input)
            val prefix = ByteArray(VxcFormat.HEADER_PREFIX_BYTES)
            val got = readUpTo(data, prefix)
            if (got < VxcFormat.MAGIC.size || !prefix.copyOfRange(0, VxcFormat.MAGIC.size).contentEquals(VxcFormat.MAGIC)) {
                throw VxcException.NotVxcFile()
            }
            if (got < VxcFormat.HEADER_PREFIX_BYTES) throw VxcException.Truncated()
            val version = prefix[8].toInt() and 0xFF
            if (version != VxcFormat.VERSION_1) throw VxcException.UnsupportedVersion(version)
            val total = ((prefix[9].toInt() and 0xFF) shl 8) or (prefix[10].toInt() and 0xFF)
            if (total < FIXED_PART || total > VxcFormat.MAX_HEADER_BYTES) {
                throw VxcException.InvalidHeader("longueur d'en-tête invalide")
            }
            val rest = ByteArray(total - VxcFormat.HEADER_PREFIX_BYTES)
            try {
                data.readFully(rest)
            } catch (e: EOFException) {
                throw VxcException.Truncated()
            }
            return parse(prefix + rest)
        }

        fun parse(encoded: ByteArray): VxcHeader {
            if (encoded.size < FIXED_PART || encoded.size > VxcFormat.MAX_HEADER_BYTES) {
                throw VxcException.InvalidHeader("longueur d'en-tête invalide")
            }
            if (!encoded.copyOfRange(0, VxcFormat.MAGIC.size).contentEquals(VxcFormat.MAGIC)) {
                throw VxcException.NotVxcFile()
            }
            val buf = ByteBuffer.wrap(encoded)
            try {
                buf.position(VxcFormat.MAGIC.size)
                val version = buf.get().toInt() and 0xFF
                if (version != VxcFormat.VERSION_1) throw VxcException.UnsupportedVersion(version)
                val declared = buf.short.toInt() and 0xFFFF
                if (declared != encoded.size) throw VxcException.InvalidHeader("longueur d'en-tête incohérente")
                val suite = buf.get().toInt() and 0xFF
                if (suite != VxcFormat.CIPHER_AES256_GCM_SEGMENTED) {
                    throw VxcException.InvalidHeader("algorithme de chiffrement inconnu ($suite)")
                }
                val protectionId = buf.get().toInt() and 0xFF
                val protection = KeyProtection.fromId(protectionId)
                    ?: throw VxcException.InvalidHeader("méthode de protection inconnue ($protectionId)")
                val flags = buf.get().toInt() and 0xFF
                if (flags != 0) throw VxcException.InvalidHeader("indicateurs réservés non nuls")
                val segmentSize = buf.int
                val noncePrefix = ByteArray(VxcFormat.NONCE_PREFIX_BYTES).also { buf.get(it) }
                var iterations = 0
                var salt = ByteArray(0)
                var wrapped = ByteArray(0)
                when (protection) {
                    KeyProtection.PASSWORD -> {
                        iterations = buf.int
                        val saltLen = buf.get().toInt() and 0xFF
                        if (saltLen !in VxcFormat.MIN_SALT_BYTES..VxcFormat.MAX_SALT_BYTES) {
                            throw VxcException.InvalidHeader("taille de sel hors limites")
                        }
                        salt = ByteArray(saltLen).also { buf.get(it) }
                    }
                    KeyProtection.DEVICE -> {
                        val len = buf.short.toInt() and 0xFFFF
                        if (len !in VxcFormat.MIN_WRAPPED_KEY_BYTES..VxcFormat.MAX_WRAPPED_KEY_BYTES) {
                            throw VxcException.InvalidHeader("clé enveloppée de taille invalide")
                        }
                        wrapped = ByteArray(len).also { buf.get(it) }
                    }
                }
                if (buf.hasRemaining()) throw VxcException.InvalidHeader("données inattendues dans l'en-tête")
                validate(protection, segmentSize, noncePrefix, iterations, salt, wrapped)
                return VxcHeader(version, suite, protection, segmentSize, noncePrefix, iterations, salt, wrapped, encoded)
            } catch (e: BufferUnderflowException) {
                throw VxcException.InvalidHeader("en-tête incomplet")
            }
        }

        private fun readUpTo(input: InputStream, target: ByteArray): Int {
            var off = 0
            while (off < target.size) {
                val n = input.read(target, off, target.size - off)
                if (n < 0) break
                off += n
            }
            return off
        }
    }
}
