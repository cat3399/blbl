package blbl.cat3399.core.image

import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.net.parseHttpUrl
import blbl.cat3399.core.net.urlEncodedPath
import blbl.cat3399.core.net.urlHost

object ImageUrl {
    private fun imageQuality(): String = runCatching { BiliClient.prefs.imageQuality }.getOrDefault("medium")

    private fun normalize(url: String): String {
        val u = url.trim()
        val fixed = when {
            u.startsWith("//") -> "https:$u"
            u.startsWith("http://") -> "https://" + u.removePrefix("http://")
            else -> u
        }
        return fixed
    }

    private fun resized(url: String?, suffix: String): String? {
        val u = url ?: return null
        if (u.isBlank()) return null
        val normalized = normalize(u)
        val httpUrl = normalized.parseHttpUrl() ?: return normalized
        val host = httpUrl.urlHost().lowercase()
        val supportsResize =
            (host == "hdslb.com" ||
                host.endsWith(".hdslb.com") ||
                host == "biliimg.com" ||
                host.endsWith(".biliimg.com")) &&
                httpUrl.urlEncodedPath().contains("/bfs/")
        if (!supportsResize) return normalized

        val queryIndex = normalized.indexOf('?')
        val base = if (queryIndex >= 0) normalized.substring(0, queryIndex) else normalized
        val query = if (queryIndex >= 0) normalized.substring(queryIndex) else ""
        if (base.contains("@")) return normalized
        return base + suffix + query
    }

    fun cover(url: String?): String? {
        val suffix = when (imageQuality()) {
            "small" -> "@320w_180h_1c.webp"
            "large" -> "@640w_360h_1c.webp"
            else -> "@480w_270h_1c.webp"
        }
        return resized(url, suffix)
    }

    fun poster(url: String?): String? {
        val suffix = when (imageQuality()) {
            "small" -> "@240w_340h_1c.webp"
            "large" -> "@480w_680h_1c.webp"
            else -> "@360w_510h_1c.webp"
        }
        return resized(url, suffix)
    }

    fun avatar(url: String?): String? = resized(url, "@80w_80h_1c.webp")

    fun commentThumbnail(url: String?): String? {
        val suffix = when (imageQuality()) {
            "small" -> "@320w_240h_1c.webp"
            "large" -> "@640w_480h_1c.webp"
            else -> "@480w_360h_1c.webp"
        }
        return resized(url, suffix)
    }
}
