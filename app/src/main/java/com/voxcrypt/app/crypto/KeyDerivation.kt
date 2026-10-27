package com.voxcrypt.app.crypto

import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Password based key derivation: PBKDF2-HMAC-SHA256, 256 bit output. */
object KeyDerivation {
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"

    /**
     * Derives a 32 byte key. The caller owns the returned array and must wipe it.
     * The [password] array is not modified; the caller wipes it as well.
     */
    fun pbkdf2(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        if (password.isEmpty()) throw VxcException.InvalidArgument("Mot de passe vide.")
        if (iterations !in VxcFormat.MIN_PBKDF2_ITERATIONS..VxcFormat.MAX_PBKDF2_ITERATIONS) {
            throw VxcException.InvalidHeader("nombre d'itérations PBKDF2 hors limites")
        }
        val spec = PBEKeySpec(password, salt, iterations, VxcFormat.KEY_SIZE_BYTES * 8)
        try {
            val factory = SecretKeyFactory.getInstance(ALGORITHM)
            return factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Measures the time of one derivation with [iterations], in milliseconds. */
    fun benchmark(iterations: Int): Long {
        val salt = ByteArray(VxcFormat.SALT_BYTES)
        val pwd = "benchmark-only".toCharArray()
        val start = System.nanoTime()
        val key = pbkdf2(pwd, salt, iterations)
        val elapsed = (System.nanoTime() - start) / 1_000_000
        key.fill(0)
        pwd.fill('\u0000')
        return elapsed
    }
}
