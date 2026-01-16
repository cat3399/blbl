package blbl.cat3399.core.net

import java.security.MessageDigest
import java.util.Locale

object AppSigner {
    // Keep consistent with PiliPlus android_hd.
    // These values are widely documented; used to sign appkey-protected endpoints.
    const val APP_KEY_ANDROID_HD = "dfca71928277209b"
    const val APP_SEC_ANDROID_HD = "b5475a8825547a4fc26c7d518eaaa02e"

    fun signQuery(
        params: Map<String, String>,
        appKey: String = APP_KEY_ANDROID_HD,
        appSec: String = APP_SEC_ANDROID_HD,
        nowEpochSec: Long = System.currentTimeMillis() / 1000,
    ): Map<String, String> {
        val out = params.toMutableMap()
        out["appkey"] = appKey
        out["ts"] = nowEpochSec.toString()
        val sorted = out.entries.sortedBy { it.key }
        val query = sorted.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        out["sign"] = md5Hex(query + appSec)
        return out
    }

    private fun enc(s: String): String = percentEncodeUtf8(s)

    // Encode like encodeURIComponent (space -> %20, hex uppercase).
    private fun percentEncodeUtf8(s: String): String {
        val bytes = s.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder(bytes.size * 3)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            val isUnreserved =
                (c in 'a'.code..'z'.code) ||
                    (c in 'A'.code..'Z'.code) ||
                    (c in '0'.code..'9'.code) ||
                    c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code
            if (isUnreserved) {
                sb.append(c.toChar())
            } else {
                sb.append('%')
                sb.append("0123456789ABCDEF"[c ushr 4])
                sb.append("0123456789ABCDEF"[c and 0x0F])
            }
        }
        return sb.toString()
    }

    private fun md5Hex(s: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append(String.format(Locale.US, "%02x", b))
        return sb.toString()
    }
}

