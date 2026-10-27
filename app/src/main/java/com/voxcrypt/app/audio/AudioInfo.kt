package com.voxcrypt.app.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/** Metadata of audio sources (duration, display name, size). */
object AudioInfo {

    fun durationOf(file: File): Long = retrieve { it.setDataSource(file.absolutePath) }

    fun durationOf(context: Context, uri: Uri): Long = retrieve { it.setDataSource(context, uri) }

    /** True when the platform can parse the file as audio (detects corrupted input). */
    fun looksLikeAudio(context: Context, uri: Uri): Boolean = try {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
        } finally {
            r.release()
        }
    } catch (e: Exception) {
        false
    }

    private inline fun retrieve(setup: (MediaMetadataRetriever) -> Unit): Long = try {
        val r = MediaMetadataRetriever()
        try {
            setup(r)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1
        } finally {
            r.release()
        }
    } catch (e: Exception) {
        -1
    }

    data class UriDetails(val displayName: String, val sizeBytes: Long, val mimeType: String)

    fun details(context: Context, uri: Uri): UriDetails {
        var name = "audio"
        var size = -1L
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val si = c.getColumnIndex(OpenableColumns.SIZE)
                        if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                        if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                    }
                }
        } catch (e: Exception) {
            // Provider without metadata: keep defaults.
        }
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        return UriDetails(name, size, mime)
    }
}
