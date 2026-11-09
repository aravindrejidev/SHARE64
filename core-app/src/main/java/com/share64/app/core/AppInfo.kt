package com.share64.app.core

import android.content.Context
import android.os.Build
import android.os.Process

/** Facts shown on the About screens (phone and TV). Edit the constants to change links. */
object AppInfo {
    const val APP_NAME = "SHARE64"
    const val TAGLINE = "Fast local Wi-Fi file sharing. Files go straight between your devices \u2014 no cloud, no account."

    const val DEVELOPER_NAME = "Aravind"
    const val DEVELOPER_GITHUB = "aravindrejidev"
    const val REPO_NAME = "SHARE64"

    const val DEVELOPER_URL = "https://github.com/$DEVELOPER_GITHUB"
    const val DEVELOPER_AVATAR_URL = "https://github.com/$DEVELOPER_GITHUB.png?size=200"
    const val REPO_URL = "$DEVELOPER_URL/$REPO_NAME"
    const val RELEASES_URL = "$REPO_URL/releases"
    const val ISSUES_URL = "$REPO_URL/issues"
    const val CONTRIBUTORS_API_URL = "https://api.github.com/repos/$DEVELOPER_GITHUB/$REPO_NAME/contributors?per_page=30"

    data class BuildInfo(val versionName: String, val versionCode: Long, val abiLabel: String)

    fun read(context: Context): BuildInfo {
        val info = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (_: Exception) {
            null
        }
        val code = when {
            info == null -> 0L
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> info.longVersionCode
            else -> @Suppress("DEPRECATION") info.versionCode.toLong()
        }
        return BuildInfo(
            versionName = info?.versionName ?: "?",
            versionCode = code,
            abiLabel = AbiLabel.of(AbiLabel.pick(Build.SUPPORTED_ABIS, Process.is64Bit())),
        )
    }
}

/** "arm64-v8a" -> "ARM64" and friends. Pure, unit-tested. */
object AbiLabel {
    fun of(abi: String?): String = when (abi) {
        null, "" -> "Unknown"
        "arm64-v8a" -> "ARM64"
        "armeabi-v7a", "armeabi" -> "ARM32"
        "x86_64" -> "x86_64"
        "x86" -> "x86"
        else -> abi.uppercase()
    }

    /** The device ABI matching this process's bitness (a 32-bit app on a 64-bit phone reports ARM32). */
    fun pick(supported: Array<String>, is64Bit: Boolean): String? =
        supported.firstOrNull { it.contains("64") == is64Bit } ?: supported.firstOrNull()
}
