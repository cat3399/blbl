package blbl.cat3399.core.net

import android.util.Base64

internal object PiliWebHeaders {
    private const val REFERER = "https://www.bilibili.com"

    fun forUrl(
        targetUrl: String,
        includeCookie: Boolean = true,
    ): Map<String, String> {
        val out = baseHeaders(includeMid = includeCookie).toMutableMap()
        out["Referer"] = REFERER
        // Tell OkHttp interceptor to not add Origin (PiliPlus does not send it for this flow).
        out["X-Blbl-Skip-Origin"] = "1"
        if (includeCookie) {
            val cookie = BiliClient.cookies.cookieHeaderFor(targetUrl)
            if (!cookie.isNullOrBlank()) out["Cookie"] = cookie
        }
        return out
    }

    private fun baseHeaders(includeMid: Boolean = true): Map<String, String> {
        val headers =
            mutableMapOf(
                "env" to "prod",
                "app-key" to "android64",
                "x-bili-aurora-zone" to "sh001",
            )
        if (!includeMid) return headers
        val midStr = BiliClient.cookies.getCookieValue("DedeUserID")?.trim().orEmpty()
        val mid = midStr.toLongOrNull()?.takeIf { it > 0 } ?: return headers
        headers["x-bili-mid"] = mid.toString()
        genAuroraEid(mid)?.let { headers["x-bili-aurora-eid"] = it }
        return headers
    }

    private fun genAuroraEid(mid: Long): String? {
        if (mid <= 0) return null
        val key = "ad1va46a7lza".toByteArray()
        val input = mid.toString().toByteArray()
        val out = ByteArray(input.size)
        for (i in input.indices) out[i] = (input[i].toInt() xor key[i % key.size].toInt()).toByte()
        return Base64.encodeToString(out, Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
