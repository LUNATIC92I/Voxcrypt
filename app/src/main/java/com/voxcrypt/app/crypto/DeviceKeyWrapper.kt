package com.voxcrypt.app.crypto

/**
 * Protects random per file data keys with a key that never leaves the device
 * (Android Keystore in production, an in-memory key in JVM tests).
 */
interface DeviceKeyWrapper {
    /** Returns an opaque blob (e.g. IV || ciphertext || tag) containing [dataKey]. */
    fun wrap(dataKey: ByteArray, associatedData: ByteArray): ByteArray

    /** Recovers the data key. Throws [VxcException.DeviceKeyUnavailable] on failure. */
    fun unwrap(wrapped: ByteArray, associatedData: ByteArray): ByteArray
}
