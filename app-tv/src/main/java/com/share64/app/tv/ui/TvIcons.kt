package com.share64.app.tv.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** A few Material Symbols that are not in the core icon set, as tiny vectors (no extra dependency). */
object TvIcons {
    val Wifi: ImageVector by lazy {
        icon(
            "Wifi",
            "M1,9l2,2c4.97,-4.97 13.03,-4.97 18,0l2,-2C16.93,2.93 7.08,2.93 1,9zM9,17l3,3 3,-3c-1.65,-1.66 -4.34,-1.66 -6,0zM5,13l2,2c2.76,-2.76 7.24,-2.76 10,0l2,-2C15.14,9.14 8.87,9.14 5,13z",
        )
    }
    val Phone: ImageVector by lazy {
        icon(
            "Phone",
            "M17,1.01L7,1c-1.1,0 -2,0.9 -2,2v18c0,1.1 0.9,2 2,2h10c1.1,0 2,-0.9 2,-2V3c0,-1.1 -0.9,-1.99 -2,-1.99zM17,19H7V5h10v14z",
        )
    }
    val Tv: ImageVector by lazy {
        icon(
            "Tv",
            "M21,3H3c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h5v2h8v-2h5c1.1,0 1.99,-0.9 1.99,-2L23,5c0,-1.1 -0.9,-2 -2,-2zM21,17H3V5h18v12z",
        )
    }
    val Devices: ImageVector by lazy {
        icon(
            "Devices",
            "M4,6h18V4H4c-1.1,0 -2,0.9 -2,2v11H0v3h14v-3H4V6zM23,8h-6c-0.55,0 -1,0.45 -1,1v10c0,0.55 0.45,1 1,1h6c0.55,0 1,-0.45 1,-1V9c0,-0.55 -0.45,-1 -1,-1zM22,17h-4v-7h4v7z",
        )
    }
    val ArrowUp: ImageVector by lazy {
        icon("ArrowUp", "M4,12l1.41,1.41L11,7.83V20h2V7.83l5.58,5.59L20,12l-8,-8 -8,8z")
    }
    val ArrowDown: ImageVector by lazy {
        icon("ArrowDown", "M20,12l-1.41,-1.41L13,16.17V4h-2v12.17l-5.58,-5.59L4,12l8,8 8,-8z")
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
