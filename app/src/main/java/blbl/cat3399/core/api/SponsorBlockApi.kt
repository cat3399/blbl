package blbl.cat3399.core.api

import blbl.cat3399.core.net.BiliClient
import org.json.JSONArray

object SponsorBlockApi {
    private const val BASE_URL = "https://www.bsbsb.top"

    data class Segment(
        val startMs: Long,
        val endMs: Long,
        val category: String?,
        val uuid: String?,
        val actionType: String?,
    )

    suspend fun skipSegments(bvid: String, cid: Long): List<Segment> {
        val safeBvid = bvid.trim()
        if (safeBvid.isBlank()) return emptyList()
        if (cid <= 0L) return emptyList()

        val url = "$BASE_URL/api/skipSegments?videoID=$safeBvid&cid=$cid"
        val raw =
            BiliClient.requestString(
                url = url,
                method = "GET",
                headers = mapOf(
                    // Keep the third-party request clean: no bilibili Origin.
                    "X-Blbl-Skip-Origin" to "1",
                    "Referer" to "$BASE_URL/",
                ),
                noCookies = true,
            )

        val arr = JSONArray(raw)
        val out = ArrayList<Segment>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val category = obj.optString("category", "").trim().takeIf { it.isNotBlank() }
            val actionType = obj.optString("actionType", "").trim().takeIf { it.isNotBlank() }
            val uuid = obj.optString("UUID", "").trim().takeIf { it.isNotBlank() }
            val segmentArr = obj.optJSONArray("segment") ?: continue
            if (segmentArr.length() < 2) continue
            val startSec = segmentArr.optDouble(0, Double.NaN)
            val endSec = segmentArr.optDouble(1, Double.NaN)
            if (!startSec.isFinite() || !endSec.isFinite()) continue
            val startMs = (startSec * 1000.0).toLong().coerceAtLeast(0L)
            val endMs = (endSec * 1000.0).toLong().coerceAtLeast(0L)
            if (endMs <= startMs) continue
            out.add(Segment(startMs = startMs, endMs = endMs, category = category, uuid = uuid, actionType = actionType))
        }
        return out
    }
}

