package com.share64.app.mobile

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.share64.app.core.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

data class Contributor(val login: String, val avatarUrl: String, val profileUrl: String, val contributions: Int)

/**
 * The About screen's only network use: GitHub avatars and the contributors list. Every failure
 * degrades gracefully (initial letter instead of an avatar, a friendly card instead of the list),
 * so the About screen also works fully offline.
 */
object AboutRepository {
    private val avatars = ConcurrentHashMap<String, ImageBitmap>()

    suspend fun avatar(url: String): ImageBitmap? {
        avatars[url]?.let { return it }
        return withContext(Dispatchers.IO) { download(url)?.also { avatars[url] = it } }
    }

    suspend fun contributors(): List<Contributor> = withContext(Dispatchers.IO) {
        try {
            val connection = open(AppInfo.CONTRIBUTORS_API_URL).apply {
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            if (connection.responseCode != 200) return@withContext emptyList()
            parse(connection.inputStream.bufferedReader().use { it.readText() })
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Drops bots and the developer (who has their own card). */
    internal fun parse(json: String): List<Contributor> {
        val array = JSONArray(json)
        val out = ArrayList<Contributor>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val login = o.optString("login")
            if (login.isEmpty() || login.endsWith("[bot]") || o.optString("type") == "Bot") continue
            if (login.equals(AppInfo.DEVELOPER_GITHUB, ignoreCase = true)) continue
            out += Contributor(
                login = login,
                avatarUrl = o.optString("avatar_url"),
                profileUrl = o.optString("html_url").ifEmpty { "https://github.com/$login" },
                contributions = o.optInt("contributions"),
            )
        }
        return out
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6_000
            readTimeout = 6_000
            instanceFollowRedirects = true
        }

    private fun download(url: String): ImageBitmap? = try {
        open(url).inputStream.use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
    } catch (_: Exception) {
        null
    }
}
