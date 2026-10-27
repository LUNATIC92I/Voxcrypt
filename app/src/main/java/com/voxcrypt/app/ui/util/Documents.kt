package com.voxcrypt.app.ui.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream

/** "Save as" contract whose MIME type is chosen at launch time. */
class CreateDocumentWithType : ActivityResultContract<CreateDocumentWithType.Request, Uri?>() {
    data class Request(val fileName: String, val mimeType: String)

    override fun createIntent(context: Context, input: Request): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.mimeType)
            .putExtra(Intent.EXTRA_TITLE, input.fileName)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

object Documents {
    fun openInput(context: Context, uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException()

    fun openOutput(context: Context, uri: Uri): OutputStream =
        try {
            context.contentResolver.openOutputStream(uri, "wt")
        } catch (e: IllegalArgumentException) {
            context.contentResolver.openOutputStream(uri) // provider without truncate mode
        } catch (e: FileNotFoundException) {
            context.contentResolver.openOutputStream(uri)
        } ?: throw FileNotFoundException()

    /** Best effort removal of a document we failed to write completely. */
    fun deleteQuietly(context: Context, uri: Uri) {
        try {
            android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (e: Exception) {
            // ignore: provider may not support deletion
        }
    }
}
