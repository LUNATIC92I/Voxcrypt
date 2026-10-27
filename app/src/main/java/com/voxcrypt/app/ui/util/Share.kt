package com.voxcrypt.app.ui.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object Share {
    /**
     * Opens the Android share sheet with a copy of the ENCRYPTED file only.
     * The password is never attached: it must be given to the recipient through
     * another channel.
     */
    fun encryptedFile(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        context.startActivity(Intent.createChooser(send, "Partager le fichier chiffré").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
