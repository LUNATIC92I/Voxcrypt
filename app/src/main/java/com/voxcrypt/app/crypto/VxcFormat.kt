package com.voxcrypt.app.crypto

/**
 * Constants and hard limits of the VOXCRYPT ".vxc" container, format version 1.
 *
 * Every value read from a file is checked against these limits before any
 * expensive or memory hungry operation (key derivation, buffer allocation).
 * See docs/FORMAT_VXC.md for the full specification.
 */
object VxcFormat {
    val MAGIC: ByteArray = "VOXCRYPT".toByteArray(Charsets.US_ASCII)
    const val VERSION_1: Int = 1
    const val CURRENT_VERSION: Int = VERSION_1
    const val FILE_EXTENSION: String = "vxc"
    const val MIME_TYPE: String = "application/x-voxcrypt"

    /** AES-256-GCM, segmented online AEAD (nonce = prefix || counter || last flag). */
    const val CIPHER_AES256_GCM_SEGMENTED: Int = 1

    const val KEY_SIZE_BYTES: Int = 32
    const val GCM_TAG_BYTES: Int = 16
    const val GCM_NONCE_BYTES: Int = 12
    const val NONCE_PREFIX_BYTES: Int = 7

    // Segment size (plaintext bytes per authenticated segment).
    const val DEFAULT_SEGMENT_SIZE: Int = 64 * 1024
    const val MIN_SEGMENT_SIZE: Int = 4 * 1024
    const val MAX_SEGMENT_SIZE: Int = 1024 * 1024

    // PBKDF2-HMAC-SHA256 parameters.
    const val DEFAULT_PBKDF2_ITERATIONS: Int = 600_000
    const val MIN_PBKDF2_ITERATIONS: Int = 100_000
    const val MAX_PBKDF2_ITERATIONS: Int = 10_000_000
    const val SALT_BYTES: Int = 32
    const val MIN_SALT_BYTES: Int = 16
    const val MAX_SALT_BYTES: Int = 64

    // Device key mode: wrapped data key (IV 12 + key 32 + tag 16 for the Keystore wrapper).
    const val MIN_WRAPPED_KEY_BYTES: Int = 28
    const val MAX_WRAPPED_KEY_BYTES: Int = 512

    /** Fixed prefix: magic(8) version(1) headerLength(2). */
    const val HEADER_PREFIX_BYTES: Int = 11
    const val MAX_HEADER_BYTES: Int = 1024

    /** Upper bound of the encrypted metadata block (original name, mime type, ...). */
    const val MAX_METADATA_BYTES: Int = 16 * 1024
    const val MAX_TEXT_FIELD_BYTES: Int = 1024

    /** Hard limit on the number of segments (32 bit counter in the nonce). */
    const val MAX_SEGMENTS: Long = 0xFFFF_FFFFL
}

/** How the content key of a file is obtained. Stored in the header. */
enum class KeyProtection(val id: Int, val label: String) {
    /** Key derived from a password with PBKDF2-HMAC-SHA256. Portable between devices. */
    PASSWORD(1, "Mot de passe (PBKDF2-HMAC-SHA256)"),

    /** Random data key wrapped by a non exportable Android Keystore key. Bound to this device. */
    DEVICE(2, "Clé de l'appareil (Android Keystore)");

    companion object {
        fun fromId(id: Int): KeyProtection? = entries.firstOrNull { it.id == id }
    }
}
