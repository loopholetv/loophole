package io.github.loopholetv.ui.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import io.github.loopholetv.ui.guide.RailItem

// 24x24 path data from Material Symbols (Apache License 2.0).
private fun pathFor(item: QuickItem): String = when (item) {
    QuickItem.SEARCH -> RailItem.SEARCH.path
    QuickItem.SETTINGS -> RailItem.SETTINGS.path
    QuickItem.CHANNELS ->
        "M3,13h2v-2H3v2zM3,17h2v-2H3v2zM3,9h2V7H3v2zM7,13h14v-2H7v2zM7,17h14v-2H7v2zM7,7v2h14V7H7z"
    QuickItem.START_OVER ->
        "M12,5V1L7,6l5,5V7c3.31,0 6,2.69 6,6s-2.69,6 -6,6 -6,-2.69 -6,-6H4c0,4.42 3.58,8 8,8s8,-3.58 8,-8 -3.58,-8 -8,-8z"
    QuickItem.VIDEO ->
        "M17,10.5V7c0,-0.55 -0.45,-1 -1,-1H4c-0.55,0 -1,0.45 -1,1v10c0,0.55 0.45,1 1,1h12c0.55,0 1,-0.45 1,-1v-3.5l4,4v-11l-4,4z"
    QuickItem.AUDIO ->
        "M12,3v9.28c-0.47,-0.17 -0.97,-0.28 -1.5,-0.28C8.01,12 6,14.01 6,16.5S8.01,21 10.5,21c2.31,0 4.2,-1.75 4.45,-4H15V6h4V3h-7z"
    QuickItem.AUDIO_DELAY ->
        "M15,1H9v2h6V1zM11,14h2V8h-2v6zM19.03,7.39l1.42,-1.42c-0.43,-0.51 -0.9,-0.99 -1.41,-1.41l-1.42,1.42" +
            "C16.07,4.74 14.12,4 12,4c-4.97,0 -9,4.03 -9,9s4.02,9 9,9 9,-4.03 9,-9c0,-2.12 -0.74,-4.07 -1.97,-5.61z" +
            "M12,20c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7 7,3.13 7,7 -3.13,7 -7,7z"
    QuickItem.SUBTITLES ->
        "M20,4H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2z" +
            "M4,12h4v2H4v-2zM14,18H4v-2h10v2zM20,18h-4v-2h4v2zM20,14H10v-2h10v2z"
    QuickItem.ASPECT ->
        "M19,12h-2v3h-3v2h5v-5zM7,9h3V7H5v5h2V9zM21,3H3c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h18c1.1,0 2,-0.9 2,-2V5" +
            "c0,-1.1 -0.9,-2 -2,-2zM21,19.01H3V4.99h18v14.02z"
    QuickItem.SLEEP ->
        "M12.34,2.02C6.59,1.82 2,6.42 2,12c0,5.52 4.48,10 10,10c3.71,0 6.93,-2.02 8.66,-5.02C13.15,16.73 8.57,8.55 12.34,2.02z"
    QuickItem.FAVOURITE ->
        "M12,17.27L18.18,21l-1.64,-7.03L22,9.24l-7.19,-0.61L12,2 9.19,8.63 2,9.24l5.46,4.73L5.82,21z"
    QuickItem.CAST ->
        "M1,18v3h3c0,-1.66 -1.34,-3 -3,-3zM1,14v2c2.76,0 5,2.24 5,5h2c0,-3.87 -3.13,-7 -7,-7zM1,10v2" +
            "c4.97,0 9,4.03 9,9h2c0,-6.08 -4.93,-11 -11,-11zM21,3H3c-1.1,0 -2,0.9 -2,2v3h2V5h18v14h-7v2h7" +
            "c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2z"
    QuickItem.OPTIONS ->
        "M6,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM18,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2" +
            " -0.9,-2 -2,-2zM12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z"
    QuickItem.STREAM_INFO ->
        "M11,7h2v2h-2zM11,11h2v6h-2zM12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2z" +
            "M12,20c-4.41,0 -8,-3.59 -8,-8s3.59,-8 8,-8 8,3.59 8,8 -3.59,8 -8,8z"
    QuickItem.PIP ->
        "M19,7h-8v6h8V7zM21,3H3c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,1.98 2,1.98h18c1.1,0 2,-0.88 2,-1.98V5" +
            "c0,-1.1 -0.9,-2 -2,-2zM21,19.01H3V4.98h18v14.03z"
}

private val icons: Map<QuickItem, ImageVector> by lazy {
    QuickItem.entries.associateWith { item ->
        ImageVector.Builder(item.label, 24.dp, 24.dp, 24f, 24f)
            .addPath(pathData = addPathNodes(pathFor(item)), fill = SolidColor(Color.White))
            .build()
    }
}

/** The tile icon for [item], tinted by the caller. */
fun quickIcon(item: QuickItem): ImageVector = icons.getValue(item)
