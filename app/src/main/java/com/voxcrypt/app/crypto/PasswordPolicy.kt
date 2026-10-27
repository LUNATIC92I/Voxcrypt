package com.voxcrypt.app.crypto

/**
 * Minimal password policy. The strength estimate is a coarse heuristic
 * (length and character variety), not a guarantee: long passphrases are
 * the recommended choice.
 */
object PasswordPolicy {
    const val MIN_LENGTH = 10

    enum class Strength(val label: String) {
        TOO_SHORT("Trop court"),
        WEAK("Faible"),
        MEDIUM("Correct"),
        STRONG("Robuste"),
    }

    fun evaluate(password: CharArray): Strength {
        if (password.size < MIN_LENGTH) return Strength.TOO_SHORT
        var classes = 0
        if (password.any { it.isLowerCase() }) classes++
        if (password.any { it.isUpperCase() }) classes++
        if (password.any { it.isDigit() }) classes++
        if (password.any { !it.isLetterOrDigit() }) classes++
        val distinct = password.toSet().size
        if (distinct < 5) return Strength.WEAK
        return when {
            password.size >= 16 && classes >= 2 -> Strength.STRONG
            password.size >= 12 && classes >= 3 -> Strength.STRONG
            classes >= 2 -> Strength.MEDIUM
            password.size >= 14 -> Strength.MEDIUM
            else -> Strength.WEAK
        }
    }

    /** A password is accepted for encryption when it is at least MEDIUM. */
    fun isAcceptable(password: CharArray): Boolean =
        evaluate(password).ordinal >= Strength.MEDIUM.ordinal
}
