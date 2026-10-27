package com.voxcrypt.app.ui.util

import java.text.DateFormat
import java.util.Date
import java.util.Locale

object Fmt {
    fun clock(millis: Long): String {
        if (millis < 0) return "--:--"
        val totalSec = millis / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%02d:%02d", m, s)
    }

    fun timer(millis: Long): String {
        val tenths = (millis % 1000) / 100
        return clock(millis) + "." + tenths
    }

    fun size(bytes: Long): String = when {
        bytes < 0 -> "inconnue"
        bytes < 1024 -> "$bytes o"
        bytes < 1024 * 1024 -> String.format(Locale.FRANCE, "%.1f Kio", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.FRANCE, "%.1f Mio", bytes / (1024.0 * 1024))
        else -> String.format(Locale.FRANCE, "%.2f Gio", bytes / (1024.0 * 1024 * 1024))
    }

    fun date(millis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.FRANCE).format(Date(millis))

    fun thousands(n: Int): String = String.format(Locale.FRANCE, "%,d", n).replace(' ', ' ').replace(' ', ' ')
}
