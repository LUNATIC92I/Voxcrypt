package com.voxcrypt.app.ui.components

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Thin line icon set drawn for VOXCRYPT (24 x 24 grid, 1.6 stroke). */
object VxIcons {
    private fun line(name: String, vararg paths: String, filled: Boolean = false): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (d in paths) {
            b.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                fill = if (filled) SolidColor(Color.White) else null,
                stroke = if (filled) null else SolidColor(Color.White),
                strokeLineWidth = 1.6f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    val Mic = line("mic", "M12 3a3 3 0 0 1 3 3v6a3 3 0 0 1 -6 0V6a3 3 0 0 1 3 -3z", "M5.5 11a6.5 6.5 0 0 0 13 0", "M12 17.5V21", "M8.5 21h7")
    val Pause = line("pause", "M8.5 5v14", "M15.5 5v14")
    val Play = line("play", "M7.5 4.5l12 7.5l-12 7.5z")
    val Stop = line("stop", "M6.5 6.5h11v11h-11z")
    val Record = line("record", "M12 6a6 6 0 1 0 0.01 0z", filled = true)
    val Lock = line("lock", "M5 10.5h14v10H5z", "M8 10.5V7a4 4 0 0 1 8 0v3.5", "M12 14.5v2.5")
    val Unlock = line("unlock", "M5 10.5h14v10H5z", "M8 10.5V7a4 4 0 0 1 7.6 -1.7", "M12 14.5v2.5")
    val Folder = line("folder", "M3 6.5h6l2 2h10v10.5H3z", "M3 11h18")
    val Shield = line("shield", "M12 3l8 3v5.5c0 4.8 -3.4 8.2 -8 9.5c-4.6 -1.3 -8 -4.7 -8 -9.5V6z", "M8.5 12l2.5 2.5l4.5 -5")
    val Back = line("back", "M14.5 5l-7 7l7 7")
    val Share = line("share", "M17.5 4a2.5 2.5 0 1 0 0.01 0z", "M6.5 9.5a2.5 2.5 0 1 0 0.01 0z", "M17.5 15a2.5 2.5 0 1 0 0.01 0z", "M8.7 10.8l6.6 -3.6", "M8.7 13.2l6.6 3.6")
    val Export = line("export", "M12 3.5v11", "M7.5 10l4.5 4.5l4.5 -4.5", "M4.5 19.5h15")
    val Import = line("import", "M12 14.5v-11", "M7.5 8l4.5 -4.5l4.5 4.5", "M4.5 19.5h15")
    val Delete = line("delete", "M4 7h16", "M9.5 7V4.5h5V7", "M6.5 7l1 13h9l1 -13", "M10.5 11v5.5", "M13.5 11v5.5")
    val Edit = line("edit", "M4.5 19.5h4l10.5 -10.5l-4 -4L4.5 15.5z", "M13 7l4 4")
    val Info = line("info", "M12 3a9 9 0 1 0 0.01 0z", "M12 11v5.5", "M12 7.6v0.2")
    val Eye = line("eye", "M2.5 12s3.5 -6.5 9.5 -6.5s9.5 6.5 9.5 6.5s-3.5 6.5 -9.5 6.5S2.5 12 2.5 12z", "M12 9.2a2.8 2.8 0 1 0 0.01 0z")
    val EyeOff = line("eyeoff", "M2.5 12s3.5 -6.5 9.5 -6.5s9.5 6.5 9.5 6.5s-3.5 6.5 -9.5 6.5S2.5 12 2.5 12z", "M4 4l16 16")
    val Check = line("check", "M5 12.5l4.5 4.5l9.5 -10")
    val Warning = line("warning", "M12 3.5l9.5 16.5h-19z", "M12 10v4.5", "M12 17.2v0.2")
    val Close = line("close", "M6 6l12 12", "M18 6L6 18")
    val File = line("file", "M14 3H6v18h12V7z", "M14 3v4h4", "M9 12.5v3", "M12 11v6", "M15 13v2")
    val Key = line("key", "M8 9.5a4.5 4.5 0 1 0 0.01 0z", "M11.2 11.2L20 20", "M16.5 16.5l2 -2", "M18.5 18.5l1.5 -1.5")
    val Chip = line("chip", "M7 7h10v10H7z", "M10 10h4v4h-4z", "M9.5 3.5V7", "M14.5 3.5V7", "M9.5 17v3.5", "M14.5 17v3.5", "M3.5 9.5H7", "M3.5 14.5H7", "M17 9.5h3.5", "M17 14.5h3.5")
    val Broom = line("clean", "M4 20l7 -7", "M9 11l4 4", "M13 15l-1.5 5.5H4l4.5 -9z", "M14 9l6 -6")
}
