package com.share64.app.core

data class ReleaseAsset(val name: String, val url: String, val size: Long)

data class ReleaseInfo(
    val tag: String,
    val title: String,
    val body: String,
    val pageUrl: String,
    val publishedAt: String?,
    val prerelease: Boolean,
    val assets: List<ReleaseAsset>,
)

/** Which of our two apps is asking for an update (selects the matching APK). */
enum class AppFlavor(val assetKey: String) { PHONE("phone"), TV("tv") }

/** Parses GitHub's "latest release" JSON. */
object ReleaseParser {
    fun parse(json: String): ReleaseInfo? {
        val root = try {
            MiniJson.parse(json)
        } catch (_: IllegalArgumentException) {
            return null
        } as? Map<*, *> ?: return null
        val tag = root["tag_name"] as? String ?: return null
        val assets = (root["assets"] as? List<*>).orEmpty().mapNotNull { a ->
            val m = a as? Map<*, *> ?: return@mapNotNull null
            val name = m["name"] as? String ?: return@mapNotNull null
            val url = m["browser_download_url"] as? String ?: return@mapNotNull null
            ReleaseAsset(name, url, (m["size"] as? Number)?.toLong() ?: -1L)
        }
        return ReleaseInfo(
            tag = tag,
            title = (root["name"] as? String).orEmpty(),
            body = (root["body"] as? String).orEmpty(),
            pageUrl = (root["html_url"] as? String).orEmpty(),
            publishedAt = root["published_at"] as? String,
            prerelease = root["prerelease"] == true,
            assets = assets,
        )
    }
}

/** Picks the right APK from a release made by our release workflow (SHARE64-<phone|tv>-<ver>-<abi|universal>-release.apk). */
object AssetPicker {
    data class Pick(val apk: ReleaseAsset, val abiLabel: String)

    const val CHECKSUMS_NAME = "SHA256SUMS.txt"

    /**
     * Prefers the APK built for the device's ABI that matches this process's bitness, then the
     * universal APK. Debug builds are never offered.
     */
    fun pick(assets: List<ReleaseAsset>, flavor: AppFlavor, supportedAbis: List<String>, is64Bit: Boolean): Pick? {
        val prefix = "SHARE64-${flavor.assetKey}-"
        val apks = assets.filter { it.name.startsWith(prefix) && it.name.endsWith("-release.apk") }
        for (abi in supportedAbis.filter { it.contains("64") == is64Bit }) {
            apks.firstOrNull { it.name.endsWith("-$abi-release.apk") }?.let { return Pick(it, AbiLabel.of(abi)) }
        }
        apks.firstOrNull { it.name.endsWith("-universal-release.apk") }?.let { return Pick(it, "Universal") }
        return null
    }

    fun checksumsAsset(assets: List<ReleaseAsset>): ReleaseAsset? = assets.firstOrNull { it.name == CHECKSUMS_NAME }
}

/** Parses the SHA256SUMS.txt that `sha256sum` writes ("<hash>  ./<file>"). */
object Checksums {
    private val hex = Regex("^[0-9a-fA-F]{64}$")

    fun parse(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            val parts = t.split(Regex("\\s+"), limit = 2)
            if (parts.size < 2 || !hex.matches(parts[0])) continue
            out[parts[1].trim().removePrefix("*").removePrefix("./")] = parts[0].lowercase()
        }
        return out
    }
}
