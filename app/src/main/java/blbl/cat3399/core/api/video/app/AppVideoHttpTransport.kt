package blbl.cat3399.core.api.video.app

import blbl.cat3399.core.net.AppSigner
import blbl.cat3399.core.net.BiliClient
import org.json.JSONObject

internal interface AppVideoHttpTransport {
    suspend fun recommend(idx: Int): JSONObject
}

internal object BiliClientAppVideoHttpTransport : AppVideoHttpTransport {
    override suspend fun recommend(idx: Int): JSONObject {
        val session = BiliClient.prefs.appAuthSession ?: error("app_auth_session_missing")
        val params =
            AppSigner.signQuery(
                mapOf(
                    "idx" to idx.coerceAtLeast(0).toString(),
                    "access_key" to session.accessKey,
                ),
            )
        val url = BiliClient.withQuery(FEED_INDEX_URL, params)
        return BiliClient.getJson(url = url, headers = appHeaders(), noCookies = true)
    }

    private fun appHeaders(): Map<String, String> =
        mapOf(
            "User-Agent" to APP_USER_AGENT,
            "Referer" to "https://app.bilibili.com",
            "X-Blbl-Skip-Origin" to "1",
        )

    private const val FEED_INDEX_URL = "https://app.bilibili.com/x/v2/feed/index"
    private const val APP_USER_AGENT =
        "Mozilla/5.0 BiliDroid/2.2.0 (bbcallen@gmail.com) os/android model/2505DRP06G mobi_app/android_hd build/2020100 channel/yingyongbao innerVer/2020100 osVer/15 network/2"
}
