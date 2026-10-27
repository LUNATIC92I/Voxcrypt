package com.voxcrypt.app.ui.util

import com.voxcrypt.app.crypto.VxcException
import com.voxcrypt.app.data.InsufficientStorageException
import java.io.FileNotFoundException
import java.io.IOException

/** User facing messages. Never includes technical details that could leak data. */
object Errors {
    fun describe(t: Throwable): String = when (t) {
        is VxcException.AuthenticationFailed ->
            "Mot de passe incorrect ou fichier altéré. Aucun audio n'a été restitué."
        is VxcException.Tampered ->
            "Fichier modifié ou endommagé : son authenticité n'a pas pu être vérifiée. Aucun audio n'a été restitué."
        is VxcException.Truncated ->
            "Fichier tronqué : il est incomplet (transfert interrompu ?). Aucun audio n'a été restitué."
        is VxcException.NotVxcFile ->
            "Format non pris en charge : ce fichier n'est pas un fichier VOXCRYPT (.vxc)."
        is VxcException.UnsupportedVersion ->
            "Format VOXCRYPT version ${t.version} non pris en charge par cette version de l'application."
        is VxcException.InvalidHeader ->
            "Paramètres cryptographiques invalides : le fichier est refusé avant toute opération."
        is VxcException.InvalidMetadata ->
            "Fichier endommagé : données internes incohérentes."
        is VxcException.DeviceKeyUnavailable ->
            "La clé de cet appareil ne peut pas ouvrir ce fichier. Il a été protégé sur un autre appareil ou l'application a été réinstallée."
        is VxcException.InvalidArgument -> t.message ?: "Paramètre invalide."
        is InsufficientStorageException ->
            "Espace de stockage insuffisant : ${Fmt.size(t.neededBytes)} nécessaires, ${Fmt.size(t.availableBytes)} disponibles."
        is FileNotFoundException -> "Fichier introuvable ou accès refusé."
        is SecurityException -> "Accès au fichier refusé par Android."
        is IOException ->
            if (t.message?.contains("ENOSPC") == true || t.message?.contains("No space") == true) {
                "Espace de stockage insuffisant."
            } else {
                "Erreur de lecture ou d'écriture du fichier."
            }
        is OutOfMemoryError -> "Mémoire insuffisante pour cette opération."
        else -> "Erreur pendant l'opération. Aucun fichier partiel n'a été conservé."
    }
}
