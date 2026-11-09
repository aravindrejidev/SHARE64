package com.share64.app.mobile.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** A few Material Symbols that are not in the core icon set, as tiny vectors (no extra dependency). */
object ShareIcons {
    val Folder: ImageVector by lazy {
        icon("Folder", "M10,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z")
    }
    val DarkMode: ImageVector by lazy {
        icon(
            "DarkMode",
            "M12,3c-4.97,0 -9,4.03 -9,9s4.03,9 9,9 9,-4.03 9,-9c0,-0.46 -0.04,-0.92 -0.1,-1.36 -0.98,1.37 -2.58,2.26 -4.4,2.26 -2.98,0 -5.4,-2.42 -5.4,-5.4 0,-1.81 0.89,-3.42 2.26,-4.4 -0.44,-0.06 -0.9,-0.1 -1.36,-0.1z",
        )
    }
    val Palette: ImageVector by lazy {
        icon(
            "Palette",
            "M12,3c-4.97,0 -9,4.03 -9,9s4.03,9 9,9c0.83,0 1.5,-0.67 1.5,-1.5 0,-0.39 -0.15,-0.74 -0.39,-1.01 -0.23,-0.26 -0.38,-0.61 -0.38,-0.99 0,-0.83 0.67,-1.5 1.5,-1.5H16c2.76,0 5,-2.24 5,-5 0,-4.42 -4.03,-8 -9,-8zM6.5,12c-0.83,0 -1.5,-0.67 -1.5,-1.5S5.67,9 6.5,9 8,9.67 8,10.5 7.33,12 6.5,12zM9.5,8C8.67,8 8,7.33 8,6.5S8.67,5 9.5,5s1.5,0.67 1.5,1.5S10.33,8 9.5,8zM14.5,8c-0.83,0 -1.5,-0.67 -1.5,-1.5S13.67,5 14.5,5s1.5,0.67 1.5,1.5S15.33,8 14.5,8zM17.5,12c-0.83,0 -1.5,-0.67 -1.5,-1.5S16.67,9 17.5,9s1.5,0.67 1.5,1.5S18.33,12 17.5,12z",
        )
    }
    val Lan: ImageVector by lazy {
        icon("Lan", "M13,22h8v-7h-3v-4h-5V9h3V2H8v7h3v2H6v4H3v7h8v-7H8v-2h8v2h-3z")
    }

    val Code: ImageVector by lazy {
        icon(
            "Code",
            "M9.4,16.6L4.8,12l4.6,-4.6L8,6l-6,6 6,6 1.4,-1.4zM14.6,16.6l4.6,-4.6 -4.6,-4.6L16,6l6,6 -6,6 -1.4,-1.4z",
        )
    }
    val GitHub: ImageVector by lazy {
        icon(
            "GitHub",
            "M12,0.297c-6.63,0 -12,5.373 -12,12 0,5.303 3.438,9.8 8.205,11.385 0.6,0.113 0.82,-0.258 0.82,-0.577 0,-0.285 -0.01,-1.04 -0.015,-2.04 -3.338,0.724 -4.042,-1.61 -4.042,-1.61C4.422,18.07 3.633,17.7 3.633,17.7c-1.087,-0.744 0.084,-0.729 0.084,-0.729 1.205,0.084 1.838,1.236 1.838,1.236 1.07,1.835 2.809,1.305 3.495,0.998 0.108,-0.776 0.417,-1.305 0.76,-1.605 -2.665,-0.3 -5.466,-1.332 -5.466,-5.93 0,-1.31 0.465,-2.38 1.235,-3.22 -0.135,-0.303 -0.54,-1.523 0.105,-3.176 0,0 1.005,-0.322 3.3,1.23 0.96,-0.267 1.98,-0.399 3,-0.405 1.02,0.006 2.04,0.138 3,0.405 2.28,-1.552 3.285,-1.23 3.285,-1.23 0.645,1.653 0.24,2.873 0.12,3.176 0.765,0.84 1.23,1.91 1.23,3.22 0,4.61 -2.805,5.625 -5.475,5.92 0.42,0.36 0.81,1.096 0.81,2.22 0,1.606 -0.015,2.896 -0.015,3.286 0,0.315 0.21,0.69 0.825,0.57C20.565,22.092 24,17.592 24,12.297c0,-6.627 -5.373,-12 -12,-12",
        )
    }

    private fun icon(name: String, pathData: String): ImageVector {
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        return try {
            builder.addPath(pathData = PathParser().parsePathString(pathData).toNodes(), fill = SolidColor(Color.Black)).build()
        } catch (_: Exception) {
            // A malformed path must never crash the UI: show an empty icon instead.
            ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).build()
        }
    }
}
