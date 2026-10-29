package com.orexii.aimarkdownreader.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** The extension's player icons (src/markup.ts), so both players look the same. */
object PlayerIcons {
    private fun icon(name: String, vararg paths: String, stroke: Set<Int> = emptySet()): ImageVector {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        paths.forEachIndexed { i, data ->
            if (i in stroke) {
                builder.addPath(addPathNodes(data), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round)
            } else {
                builder.addPath(addPathNodes(data), fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero)
            }
        }
        return builder.build()
    }

    val Play = icon("play", "M8 5v14l11-7z")
    val Pause = icon("pause", "M7 5h4v14H7zm6 0h4v14h-4z")
    /** Back and forward arrows; the number of seconds is drawn over them. */
    val Back = icon("back", "M12 5V2L7 6l5 4V7a6 6 0 1 1-6 6H4a8 8 0 1 0 8-8z")
    val Forward = icon("forward", "M12 5V2l5 4-5 4V7a6 6 0 1 0 6 6h2a8 8 0 1 1-8-8z")
    val Previous = icon("previous", "M6 5h2v14H6zm3.5 7L18 19V5z")
    val Next = icon("next", "M16 5h2v14h-2zM6 5v14l8.5-7z")
    val Settings = icon(
        "settings",
        "M19.4 13a7.6 7.6 0 0 0 0-2l2.1-1.6-2-3.5-2.5 1a7.4 7.4 0 0 0-1.7-1L15 3h-4l-.4 2.9a7.4 7.4 0 0 0-1.7 1l-2.5-1-2 3.5L6.6 11a7.6 7.6 0 0 0 0 2l-2.2 1.6 2 3.5 2.5-1c.5.4 1.1.7 1.7 1L11 21h4l.4-2.9c.6-.3 1.2-.6 1.7-1l2.5 1 2-3.5zM13 15.5a3.5 3.5 0 1 1 0-7 3.5 3.5 0 0 1 0 7z",
    )
    val Speaker = icon("speaker", "M3 9.5h4l5-4.5v14l-5-4.5H3z", "M15.5 9a4 4 0 0 1 0 6M18.5 6a8.5 8.5 0 0 1 0 12", stroke = setOf(1))
    val ArrowBack = icon("arrowBack", "M20 11H7.8l5.6-5.6L12 4l-8 8 8 8 1.4-1.4L7.8 13H20z")
    val Close = icon("close", "M19 6.4 17.6 5 12 10.6 6.4 5 5 6.4 10.6 12 5 17.6 6.4 19 12 13.4 17.6 19 19 17.6 13.4 12z")
    val Folder = icon("folder", "M10 4H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-8z")
    val File = icon("file", "M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8zm-1 7V3.5L18.5 9z")
    val Top = icon("top", "M6 4h12v2H6zm6 3-6 6h4v7h4v-7h4z")
}
