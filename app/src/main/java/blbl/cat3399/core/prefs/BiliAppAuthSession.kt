package blbl.cat3399.core.prefs

import org.json.JSONArray
import org.json.JSONObject

data class BiliAppAuthSession(
    val accessToken: String,
    val refreshToken: String?,
    val mid: Long?,
    val expiresInSec: Long?,
    val issuedAtMs: Long,
    val expiresAtMs: Long?,
    val appKey: String,
    val mobiApp: String?,
    val platform: String?,
    val localId: String?,
    val isNew: Boolean?,
    val ssoUrls: List<String>,
    val cookieDomains: List<String>,
    val rawDataJson: String,
) {
    val accessKey: String
        get() = accessToken

    fun toJson(): JSONObject {
        val root =
            JSONObject()
                .put(KEY_ACCESS_TOKEN, accessToken)
                .put(KEY_ACCESS_KEY, accessToken)
                .put(KEY_ISSUED_AT_MS, issuedAtMs)
                .put(KEY_APPKEY, appKey)
                .put(KEY_SSO, ssoUrls.toJsonArray())
                .put(KEY_COOKIE_INFO_DOMAINS, cookieDomains.toJsonArray())
                .put(KEY_RAW_DATA, rawDataJson)

        refreshToken?.let { root.put(KEY_REFRESH_TOKEN, it) }
        mid?.let { root.put(KEY_MID, it) }
        expiresInSec?.let { root.put(KEY_EXPIRES_IN_SEC, it) }
        expiresAtMs?.let { root.put(KEY_EXPIRES_AT_MS, it) }
        mobiApp?.let { root.put(KEY_MOBI_APP, it) }
        platform?.let { root.put(KEY_PLATFORM, it) }
        localId?.let { root.put(KEY_LOCAL_ID, it) }
        isNew?.let { root.put(KEY_IS_NEW, it) }
        return root
    }

    companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_ACCESS_KEY = "access_key"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_MID = "mid"
        private const val KEY_EXPIRES_IN_SEC = "expires_in_sec"
        private const val KEY_EXPIRES_AT_MS = "expires_at_ms"
        private const val KEY_ISSUED_AT_MS = "issued_at_ms"
        private const val KEY_APPKEY = "appkey"
        private const val KEY_MOBI_APP = "mobi_app"
        private const val KEY_PLATFORM = "platform"
        private const val KEY_LOCAL_ID = "local_id"
        private const val KEY_IS_NEW = "is_new"
        private const val KEY_SSO = "sso"
        private const val KEY_COOKIE_INFO_DOMAINS = "cookie_info_domains"
        private const val KEY_RAW_DATA = "raw_data"

        fun fromTvLoginData(
            data: JSONObject,
            appKey: String,
            mobiApp: String?,
            platform: String?,
            localId: String?,
            issuedAtMs: Long = System.currentTimeMillis(),
        ): BiliAppAuthSession? {
            val tokenInfo = data.optJSONObject("token_info") ?: data
            val accessToken =
                firstNonBlank(
                    tokenInfo.stringOrNull(KEY_ACCESS_TOKEN),
                    tokenInfo.stringOrNull(KEY_ACCESS_KEY),
                    data.stringOrNull(KEY_ACCESS_TOKEN),
                    data.stringOrNull(KEY_ACCESS_KEY),
                ) ?: return null

            val refreshToken =
                firstNonBlank(
                    tokenInfo.stringOrNull(KEY_REFRESH_TOKEN),
                    data.stringOrNull(KEY_REFRESH_TOKEN),
                )
            val mid = firstPositiveLong(tokenInfo.longOrNull(KEY_MID), data.longOrNull(KEY_MID))
            val expiresInSec = firstPositiveLong(tokenInfo.longOrNull("expires_in"), data.longOrNull("expires_in"))
            val expiresAtMs = expiresInSec?.let { issuedAtMs + it * 1000L }
            val cookieInfo = data.optJSONObject("cookie_info")

            return BiliAppAuthSession(
                accessToken = accessToken,
                refreshToken = refreshToken,
                mid = mid,
                expiresInSec = expiresInSec,
                issuedAtMs = issuedAtMs,
                expiresAtMs = expiresAtMs,
                appKey = appKey,
                mobiApp = mobiApp?.trim()?.takeIf { it.isNotBlank() },
                platform = platform?.trim()?.takeIf { it.isNotBlank() },
                localId = localId?.trim()?.takeIf { it.isNotBlank() },
                isNew = data.booleanOrNull(KEY_IS_NEW),
                ssoUrls = data.optJSONArray(KEY_SSO).stringList(),
                cookieDomains = cookieInfo?.optJSONArray("domains").stringList(),
                rawDataJson = sanitizedRawDataJson(data),
            )
        }

        fun fromJson(root: JSONObject): BiliAppAuthSession? {
            val accessToken =
                firstNonBlank(
                    root.stringOrNull(KEY_ACCESS_TOKEN),
                    root.stringOrNull(KEY_ACCESS_KEY),
                ) ?: return null
            val issuedAtMs = root.longOrNull(KEY_ISSUED_AT_MS) ?: return null
            val appKey = root.stringOrNull(KEY_APPKEY) ?: return null
            val expiresInSec = root.longOrNull(KEY_EXPIRES_IN_SEC)?.takeIf { it > 0L }

            return BiliAppAuthSession(
                accessToken = accessToken,
                refreshToken = root.stringOrNull(KEY_REFRESH_TOKEN),
                mid = root.longOrNull(KEY_MID)?.takeIf { it > 0L },
                expiresInSec = expiresInSec,
                issuedAtMs = issuedAtMs,
                expiresAtMs =
                    root.longOrNull(KEY_EXPIRES_AT_MS)
                        ?.takeIf { it > 0L }
                        ?: expiresInSec?.let { issuedAtMs + it * 1000L },
                appKey = appKey,
                mobiApp = root.stringOrNull(KEY_MOBI_APP),
                platform = root.stringOrNull(KEY_PLATFORM),
                localId = root.stringOrNull(KEY_LOCAL_ID),
                isNew = root.booleanOrNull(KEY_IS_NEW),
                ssoUrls = root.optJSONArray(KEY_SSO).stringList(),
                cookieDomains = root.optJSONArray(KEY_COOKIE_INFO_DOMAINS).stringList(),
                rawDataJson = root.stringOrNull(KEY_RAW_DATA) ?: JSONObject().toString(),
            )
        }

        private fun sanitizedRawDataJson(data: JSONObject): String {
            val copy = JSONObject(data.toString())
            copy.optJSONObject("cookie_info")?.remove("cookies")
            return copy.toString()
        }

        private fun firstNonBlank(vararg values: String?): String? =
            values.firstOrNull { !it.isNullOrBlank() }?.trim()

        private fun firstPositiveLong(vararg values: Long?): Long? =
            values.firstOrNull { it != null && it > 0L }

        private fun JSONObject.stringOrNull(key: String): String? {
            if (!has(key) || isNull(key)) return null
            return optString(key, "").trim().takeIf { it.isNotBlank() }
        }

        private fun JSONObject.longOrNull(key: String): Long? {
            if (!has(key) || isNull(key)) return null
            val value = opt(key)
            return when (value) {
                is Number -> value.toLong()
                is String -> value.trim().toLongOrNull()
                else -> null
            }
        }

        private fun JSONObject.booleanOrNull(key: String): Boolean? {
            if (!has(key) || isNull(key)) return null
            return optBoolean(key)
        }

        private fun JSONArray?.stringList(): List<String> {
            if (this == null) return emptyList()
            val out = ArrayList<String>(length())
            for (index in 0 until length()) {
                val value = optString(index, "").trim()
                if (value.isNotBlank()) out.add(value)
            }
            return out
        }

        private fun List<String>.toJsonArray(): JSONArray {
            val arr = JSONArray()
            forEach(arr::put)
            return arr
        }
    }
}
