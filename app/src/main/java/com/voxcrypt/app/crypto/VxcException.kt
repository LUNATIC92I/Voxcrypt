package com.voxcrypt.app.crypto

/**
 * Errors raised by the VOXCRYPT engine. Messages never contain key material,
 * passwords or plaintext.
 */
sealed class VxcException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The data does not start with the VOXCRYPT signature. */
    class NotVxcFile : VxcException("Ce fichier n'est pas un fichier VOXCRYPT.")

    /** Valid signature, but a format version this build does not understand. */
    class UnsupportedVersion(val version: Int) :
        VxcException("Version de format non prise en charge ($version).")

    /** Header fields outside of the accepted limits or inconsistent. */
    class InvalidHeader(reason: String) :
        VxcException("Paramètres cryptographiques invalides : $reason")

    /** The file ends before the authenticated final segment. */
    class Truncated : VxcException("Le fichier est tronqué ou incomplet.")

    /**
     * The first segment could not be authenticated. Deliberately does not say
     * whether the password is wrong or the file was altered.
     */
    class AuthenticationFailed :
        VxcException("Mot de passe incorrect ou fichier altéré.")

    /** A later segment failed authentication: content modified, reordered or cut. */
    class Tampered(cause: Throwable? = null) :
        VxcException("Le fichier a été modifié ou endommagé.", cause)

    /** Decrypted metadata block is malformed (should never happen without a tag failure). */
    class InvalidMetadata(reason: String) :
        VxcException("Métadonnées chiffrées invalides : $reason")

    /** Device bound key missing (other device, app reinstalled, key invalidated). */
    class DeviceKeyUnavailable(cause: Throwable? = null) :
        VxcException("La clé de cet appareil est indisponible pour ce fichier.", cause)

    /** Invalid input supplied by the caller (empty password, reused key, ...). */
    class InvalidArgument(reason: String) : VxcException(reason)
}
