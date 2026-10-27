package com.voxcrypt.app.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

/**
 * Metadata stored INSIDE the encrypted stream (never in clear text).
 * It precedes the audio bytes in the plaintext: u32 length, then this block.
 */
data class VxcMetadata(
    val originalName: String,
    val mimeType: String,
    val createdAtMillis: Long,
    /** Audio duration in milliseconds, or -1 when unknown. */
    val durationMillis: Long = -1,
    /** Audio length in bytes, or -1 when unknown at encryption time. */
    val audioLength: Long = -1,
) {
    fun encode(): ByteArray {
        val name = originalName.toByteArray(Charsets.UTF_8)
        val mime = mimeType.toByteArray(Charsets.UTF_8)
        if (name.size > VxcFormat.MAX_TEXT_FIELD_BYTES || mime.size > VxcFormat.MAX_TEXT_FIELD_BYTES) {
            throw VxcException.InvalidArgument("Nom ou type de fichier trop long.")
        }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeByte(METADATA_VERSION)
            out.writeShort(name.size)
            out.write(name)
            out.writeShort(mime.size)
            out.write(mime)
            out.writeLong(createdAtMillis)
            out.writeLong(durationMillis)
            out.writeLong(audioLength)
        }
        return bytes.toByteArray()
    }

    companion object {
        const val METADATA_VERSION = 1

        fun decode(bytes: ByteArray): VxcMetadata {
            try {
                DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                    val version = input.readUnsignedByte()
                    if (version != METADATA_VERSION) throw VxcException.InvalidMetadata("version $version")
                    val name = readText(input)
                    val mime = readText(input)
                    val created = input.readLong()
                    val duration = input.readLong()
                    val length = input.readLong()
                    if (input.available() != 0) throw VxcException.InvalidMetadata("données superflues")
                    if (length < -1 || duration < -1) throw VxcException.InvalidMetadata("valeurs négatives")
                    return VxcMetadata(name, mime, created, duration, length)
                }
            } catch (e: EOFException) {
                throw VxcException.InvalidMetadata("bloc incomplet")
            }
        }

        private fun readText(input: DataInputStream): String {
            val len = input.readUnsignedShort()
            if (len > VxcFormat.MAX_TEXT_FIELD_BYTES) throw VxcException.InvalidMetadata("champ texte trop long")
            val raw = ByteArray(len)
            input.readFully(raw)
            return String(raw, Charsets.UTF_8)
        }
    }
}
