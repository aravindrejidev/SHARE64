package com.share64.app.core

import java.util.Locale

/** Human-readable sizes, speeds and durations. Pure Kotlin. */
object Formatting {
    private val units = arrayOf("KB", "MB", "GB", "TB")

    fun bytes(n: Long, locale: Locale = Locale.getDefault()): String {
        if (n < 1024) return "${maxOf(n, 0)} B"
        var v = n.toDouble()
        var i = -1
        do {
            v /= 1024.0
            i++
        } while (v >= 1024.0 && i < units.lastIndex)
        val pattern = when {
            v >= 100 -> "%.0f %s"
            v >= 10 -> "%.1f %s"
            else -> "%.2f %s"
        }
        return String.format(locale, pattern, v, units[i])
    }

    fun speed(bytesPerSecond: Long, locale: Locale = Locale.getDefault()): String =
        bytes(bytesPerSecond, locale) + "/s"

    fun duration(totalSeconds: Long): String {
        val s = maxOf(totalSeconds, 0)
        return when {
            s < 60 -> "$s s"
            s < 3600 -> "${s / 60} min ${s % 60} s"
            else -> "${s / 3600} h ${(s % 3600) / 60} min"
        }
    }

    /** Null when the speed is unknown or nothing remains. */
    fun eta(remainingBytes: Long, bytesPerSecond: Long): String? {
        if (remainingBytes <= 0 || bytesPerSecond <= 0) return null
        return duration((remainingBytes + bytesPerSecond - 1) / bytesPerSecond)
    }
}
