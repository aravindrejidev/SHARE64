package com.share64.app.core

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Everything the UI needs to offer one update. */
data class UpdateInfo(
    val tag: String,
    val version: String,
    val title: String,
    /** Markdown from Relece_notice.md (may be empty). */
    val notes: String,
    val publishedAt: String?,
    val assetName: String,
    val assetUrl: String,
    val assetSize: Long,
    val checksumsUrl: String?,
    val abiLabel: String,
    val pageUrl: String,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAtMs: Long) : UpdateState
    data class Available(val update: UpdateInfo) : UpdateState
    data class Downloading(val update: UpdateInfo, val downloaded: Long, val total: Long) : UpdateState
    /** Downloaded and verified; [note] explains why we are back here (e.g. installation cancelled). */
    data class Ready(val update: UpdateInfo, val file: File, val note: String? = null) : UpdateState
    /** The system must first allow this app to install other apps. */
    data class NeedsPermission(val update: UpdateInfo, val file: File) : UpdateState
    data class Installing(val update: UpdateInfo) : UpdateState
    data class Failed(val message: String, val update: UpdateInfo? = null) : UpdateState
}

val UpdateState.updateInfo: UpdateInfo?
    get() = when (this) {
        is UpdateState.Available -> update
        is UpdateState.Downloading -> update
        is UpdateState.Ready -> update
        is UpdateState.NeedsPermission -> update
        is UpdateState.Installing -> update
        is UpdateState.Failed -> update
        else -> null
    }

/** True while there is something to show in the update sheet. */
val UpdateState.isActionable: Boolean get() = updateInfo != null

object RelativeTime {
    fun ago(deltaMs: Long): String {
        val s = (deltaMs / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            s < 86_400 -> "${s / 3600} h ago"
            else -> "${s / 86_400} days ago"
        }
    }
}

object ReleaseDates {
    /** "2026-11-10T01:20:30Z" -> "10 Nov 2026"; null when it cannot be parsed. */
    fun format(iso: String?, locale: Locale = Locale.getDefault()): String? {
        if (iso.isNullOrBlank()) return null
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }
            val date: Date = parser.parse(iso) ?: return null
            SimpleDateFormat("d MMM yyyy", locale).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(date)
        } catch (_: Exception) {
            null
        }
    }
}

object UpdateText {
    /** One-line status for the Settings row. */
    fun settingsStatus(state: UpdateState, lastCheckMs: Long, nowMs: Long): String = when (state) {
        UpdateState.Checking -> "Checking\u2026"
        is UpdateState.Available, is UpdateState.Downloading, is UpdateState.Ready,
        is UpdateState.NeedsPermission, is UpdateState.Installing -> "Version ${state.updateInfo?.version} is available \u2014 tap to update"
        is UpdateState.Failed -> state.message
        is UpdateState.UpToDate, UpdateState.Idle ->
            if (lastCheckMs > 0) "You\u2019re up to date \u00B7 checked ${RelativeTime.ago(nowMs - lastCheckMs)}"
            else "Tap to check for a new version"
    }
}
