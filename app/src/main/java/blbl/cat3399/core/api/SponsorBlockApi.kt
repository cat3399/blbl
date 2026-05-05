package blbl.cat3399.core.api

import blbl.cat3399.BuildConfig
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.net.toMediaTypeCompat
import blbl.cat3399.core.net.toRequestBodyCompat
import blbl.cat3399.core.prefs.AppPrefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

object SponsorBlockApi {
    private const val TAG = "SponsorBlockApi"
    private const val PROJECT_URL = "https://github.com/cat3399/blbl"
    private const val ACTION_POI = "poi"

    enum class FetchState {
        SUCCESS,
        NOT_FOUND,
        ERROR,
    }

    data class Segment(
        val cid: Long?,
        val startMs: Long,
        val endMs: Long,
        val category: String?,
        val uuid: String?,
        val actionType: String?,
    )

    internal data class RawSegment(
        val cid: String?,
        val category: String?,
        val actionType: String?,
        val uuid: String?,
        val startSec: Double,
        val endSec: Double,
    )

    data class FetchResult(
        val state: FetchState,
        val segments: List<Segment> = emptyList(),
        val detail: String? = null,
    )

    enum class SubmitState {
        SUCCESS,
        ERROR,
    }

    data class SubmitSegment(
        val startMs: Long,
        val endMs: Long,
        val category: String,
        val actionType: String = "skip",
    )

    data class SubmittedSegment(
        val uuid: String?,
        val startMs: Long,
        val endMs: Long,
        val category: String?,
    )

    data class SubmitResult(
        val state: SubmitState,
        val segments: List<SubmittedSegment> = emptyList(),
        val httpCode: Int = 0,
        val detail: String? = null,
    )

    private data class RequestAttempt(
        val result: FetchResult,
        val isNetworkFailure: Boolean,
    )

    suspend fun skipSegments(bvid: String, cid: Long, forceRefresh: Boolean = false): FetchResult {
        val safeBvid = bvid.trim()
        if (safeBvid.isBlank() || cid <= 0L) {
            return FetchResult(state = FetchState.NOT_FOUND, detail = "invalid_args")
        }

        val exact = querySkipSegments(bvid = safeBvid, cid = cid, forceRefresh = forceRefresh)
        if (exact.state == FetchState.SUCCESS && exact.segments.isNotEmpty()) {
            return exact.annotate("exact_cid").also { logResult(safeBvid, cid, it) }
        }

        val fallback = querySkipSegments(bvid = safeBvid, cid = null, forceRefresh = forceRefresh)
        val result =
            when (fallback.state) {
                FetchState.SUCCESS -> {
                    val picked = pickSegmentsForCid(fallback.segments, cid)
                    if (picked.isNotEmpty()) {
                        fallback.copy(segments = picked).annotate("fallback_no_cid")
                    } else {
                        fallback
                            .copy(state = FetchState.NOT_FOUND, segments = emptyList())
                            .annotate(if (fallback.segments.isEmpty()) "fallback_empty" else "fallback_cid_mismatch")
                    }
                }

                FetchState.NOT_FOUND -> {
                    when (exact.state) {
                        FetchState.SUCCESS -> exact.copy(state = FetchState.NOT_FOUND, segments = emptyList()).annotate("exact_empty")
                        FetchState.NOT_FOUND -> FetchResult(state = FetchState.NOT_FOUND, detail = "not_found")
                        FetchState.ERROR -> FetchResult(state = FetchState.NOT_FOUND, detail = "fallback_not_found")
                    }
                }

                FetchState.ERROR -> {
                    when (exact.state) {
                        FetchState.SUCCESS -> fallback.annotate("exact_empty")
                        FetchState.NOT_FOUND -> fallback.annotate("exact_not_found")
                        FetchState.ERROR -> {
                            val exactDetail = exact.detail ?: "exact_error"
                            val fallbackDetail = fallback.detail ?: "fallback_error"
                            FetchResult(state = FetchState.ERROR, detail = "exact=$exactDetail; fallback=$fallbackDetail")
                        }
                    }
                }
            }
        logResult(safeBvid, cid, result)
        return result
    }

    suspend fun submitSkipSegments(
        bvid: String,
        cid: Long,
        userId: String,
        videoDurationMs: Long,
        segments: List<SubmitSegment>,
    ): SubmitResult {
        val safeBvid = bvid.trim()
        val safeUserId = userId.trim()
        val validSegments =
            segments.filter { segment ->
                segment.startMs >= 0L &&
                    segment.endMs > segment.startMs &&
                    segment.category.trim().isNotBlank() &&
                    segment.actionType.trim().isNotBlank()
            }
        if (safeBvid.isBlank() || cid <= 0L || safeUserId.length < 30 || validSegments.isEmpty()) {
            return SubmitResult(state = SubmitState.ERROR, detail = "invalid_args")
        }

        val baseUrl = BiliClient.prefs.playerAutoSkipServerBaseUrl
        val url = "$baseUrl/api/skipSegments"
        val bodyText =
            buildSubmitSkipSegmentsJson(
                bvid = safeBvid,
                cid = cid,
                userId = safeUserId,
                userAgent = "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}",
                videoDurationMs = videoDurationMs,
                segments = validSegments,
            ).toString()
        return runCatching {
            BiliClient.requestStringResponse(
                url = url,
                method = "POST",
                headers = sponsorBlockRequestHeaders(),
                body = bodyText.toRequestBodyCompat("application/json; charset=utf-8".toMediaTypeCompat()),
                noCookies = true,
            )
        }.fold(
            onSuccess = { response ->
                if (!response.isSuccessful) {
                    return SubmitResult(
                        state = SubmitState.ERROR,
                        httpCode = response.code,
                        detail = "http_${response.code}: ${extractErrorMessage(response.body)}",
                    ).also { logSubmitResult(safeBvid, cid, it) }
                }
                val parsed =
                    runCatching { parseSubmitResponse(response.body) }
                        .getOrElse { t ->
                            return SubmitResult(
                                state = SubmitState.ERROR,
                                httpCode = response.code,
                                detail = "parse:${t.message.orEmpty()}",
                            ).also { logSubmitResult(safeBvid, cid, it) }
                        }
                SubmitResult(
                    state = SubmitState.SUCCESS,
                    segments = parsed,
                    httpCode = response.code,
                    detail = "base=$baseUrl",
                ).also { logSubmitResult(safeBvid, cid, it) }
            },
            onFailure = { t ->
                SubmitResult(
                    state = SubmitState.ERROR,
                    detail = "${t.javaClass.simpleName}:${t.message.orEmpty()} base=$baseUrl",
                ).also { logSubmitResult(safeBvid, cid, it) }
            },
        )
    }

    private suspend fun querySkipSegments(bvid: String, cid: Long?, forceRefresh: Boolean): FetchResult {
        val primaryBaseUrl = BiliClient.prefs.playerAutoSkipServerBaseUrl
        val primary = querySkipSegmentsOnce(baseUrl = primaryBaseUrl, bvid = bvid, cid = cid, forceRefresh = forceRefresh)
        if (!primary.isNetworkFailure || primaryBaseUrl == AppPrefs.FALLBACK_PLAYER_AUTO_SKIP_SERVER_BASE_URL) {
            return primary.result
        }

        AppLog.w(
            TAG,
            "skipSegments primary network failure, retry fallback base=${AppPrefs.FALLBACK_PLAYER_AUTO_SKIP_SERVER_BASE_URL} " +
                "bvid=$bvid ${requestScopeLabel(cid)} detail=${primary.result.detail.orEmpty()}",
        )
        val fallback =
            querySkipSegmentsOnce(
                baseUrl = AppPrefs.FALLBACK_PLAYER_AUTO_SKIP_SERVER_BASE_URL,
                bvid = bvid,
                cid = cid,
                forceRefresh = forceRefresh,
            )
        if (fallback.isNetworkFailure) {
            val primaryDetail = primary.result.detail ?: "primary_network_error"
            val fallbackDetail = fallback.result.detail ?: "fallback_network_error"
            return FetchResult(state = FetchState.ERROR, detail = "primary=$primaryDetail; retry=$fallbackDetail")
        }
        return fallback.result.annotate("retry_fallback_ip")
    }

    private suspend fun querySkipSegmentsOnce(baseUrl: String, bvid: String, cid: Long?, forceRefresh: Boolean): RequestAttempt {
        val url = buildSkipSegmentsUrl(baseUrl = baseUrl, bvid = bvid, cid = cid)
        return runCatching {
            BiliClient.requestStringResponse(
                url = url,
                method = "GET",
                headers = sponsorBlockRequestHeaders(forceRefresh = forceRefresh),
                noCookies = true,
            )
        }.fold(
            onSuccess = { response ->
                RequestAttempt(
                    result =
                        when {
                            response.code == 404 ->
                                FetchResult(
                                    state = FetchState.NOT_FOUND,
                                    detail = requestDetail(cid = cid, baseUrl = baseUrl),
                                )

                            !response.isSuccessful ->
                                FetchResult(
                                    state = FetchState.ERROR,
                                    detail = "${requestDetail(cid = cid, baseUrl = baseUrl)} http_${response.code}",
                                )

                            else -> {
                                runCatching { parseSkipSegments(response.body) }
                                    .fold(
                                        onSuccess = { parsed ->
                                            FetchResult(
                                                state = FetchState.SUCCESS,
                                                segments = parsed,
                                                detail = requestDetail(cid = cid, baseUrl = baseUrl),
                                            )
                                        },
                                        onFailure = { t ->
                                            FetchResult(
                                                state = FetchState.ERROR,
                                                detail = "${requestDetail(cid = cid, baseUrl = baseUrl)} parse:${t.message.orEmpty()}",
                                            )
                                        },
                                    )
                            }
                        },
                    isNetworkFailure = false,
                )
            },
            onFailure = { t ->
                RequestAttempt(
                    result =
                        FetchResult(
                            state = FetchState.ERROR,
                            detail = "${requestDetail(cid = cid, baseUrl = baseUrl)} ${t.javaClass.simpleName}:${t.message.orEmpty()}",
                        ),
                    isNetworkFailure = isRetryableNetworkFailure(t),
                )
            },
        )
    }

    private fun logResult(bvid: String, cid: Long, result: FetchResult) {
        val detail = result.detail?.takeIf { it.isNotBlank() } ?: "-"
        AppLog.i(TAG, "skipSegments bvid=$bvid cid=$cid state=${result.state.name.lowercase()} count=${result.segments.size} detail=$detail")
    }

    private fun logSubmitResult(bvid: String, cid: Long, result: SubmitResult) {
        val detail = result.detail?.takeIf { it.isNotBlank() } ?: "-"
        AppLog.i(
            TAG,
            "submitSkipSegments bvid=$bvid cid=$cid state=${result.state.name.lowercase()} " +
                "http=${result.httpCode} count=${result.segments.size} detail=$detail",
        )
    }

    private fun requestScopeLabel(cid: Long?): String = if (cid != null && cid > 0L) "cid=$cid" else "cid=all"

    private fun requestDetail(cid: Long?, baseUrl: String): String = "${requestScopeLabel(cid)} base=$baseUrl"

    private fun FetchResult.annotate(label: String): FetchResult {
        if (label.isBlank()) return this
        val detailText = detail?.takeIf { it.isNotBlank() }
        return copy(detail = if (detailText == null) label else "$label; $detailText")
    }

    private fun buildSkipSegmentsUrl(baseUrl: String, bvid: String, cid: Long?): String =
        buildString {
            append(baseUrl)
            append("/api/skipSegments?videoID=")
            append(bvid)
            if (cid != null && cid > 0L) {
                append("&cid=")
                append(cid)
            }
        }

    private fun isRetryableNetworkFailure(t: Throwable): Boolean = t is IOException

    internal fun sponsorBlockRequestHeaders(forceRefresh: Boolean = false): Map<String, String> =
        buildMap {
            put("Origin", PROJECT_URL)
            put("Referer", PROJECT_URL)
            put("X-Ext-Version", "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}")
            if (forceRefresh) put("X-Skip-Cache", "1")
        }

    internal fun buildSubmitSkipSegmentsJson(
        bvid: String,
        cid: Long,
        userId: String,
        userAgent: String,
        videoDurationMs: Long,
        segments: List<SubmitSegment>,
    ): JSONObject =
        JSONObject()
            .put("videoID", bvid)
            .put("cid", cid.toString())
            .put("userID", userId)
            .put("userAgent", userAgent)
            .put("videoDuration", videoDurationMs.coerceAtLeast(0L) / 1000.0)
            .put(
                "segments",
                JSONArray().also { arr ->
                    for (segment in segments) {
                        arr.put(
                            JSONObject()
                                .put(
                                    "segment",
                                    JSONArray()
                                        .put(segment.startMs.coerceAtLeast(0L) / 1000.0)
                                        .put(segment.endMs.coerceAtLeast(0L) / 1000.0),
                                )
                                .put("category", segment.category.trim())
                                .put("actionType", segment.actionType.trim()),
                        )
                    }
                },
            )

    internal fun parseSubmitResponse(raw: String): List<SubmittedSegment> {
        val arr = JSONArray(raw)
        val out = ArrayList<SubmittedSegment>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val uuid = obj.optString("UUID", "").trim().takeIf { it.isNotBlank() }
            val category = obj.optString("category", "").trim().takeIf { it.isNotBlank() }
            val segmentArr = obj.optJSONArray("segment") ?: continue
            if (segmentArr.length() < 2) continue
            val startSec = segmentArr.optDouble(0, Double.NaN)
            val endSec = segmentArr.optDouble(1, Double.NaN)
            if (!startSec.isFinite() || !endSec.isFinite()) continue
            out.add(
                SubmittedSegment(
                    uuid = uuid,
                    startMs = (startSec * 1000.0).toLong().coerceAtLeast(0L),
                    endMs = (endSec * 1000.0).toLong().coerceAtLeast(0L),
                    category = category,
                ),
            )
        }
        return out
    }

    private fun extractErrorMessage(raw: String): String {
        val text = raw.trim()
        if (text.isBlank()) return ""
        val obj = runCatching { JSONObject(text) }.getOrNull()
        val message =
            obj?.optString("message", "")?.trim()?.takeIf { it.isNotBlank() }
                ?: obj?.optString("error", "")?.trim()?.takeIf { it.isNotBlank() }
        return message ?: text.take(200)
    }

    internal fun parseSkipSegments(raw: String): List<Segment> {
        val arr = JSONArray(raw)
        val items = ArrayList<RawSegment>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val category = obj.optString("category", "").trim().takeIf { it.isNotBlank() }
            val actionType = obj.optString("actionType", "").trim().takeIf { it.isNotBlank() }
            val uuid = obj.optString("UUID", "").trim().takeIf { it.isNotBlank() }
            val cid = obj.optString("cid", "").trim().takeIf { it.isNotBlank() }
            val segmentArr = obj.optJSONArray("segment") ?: continue
            if (segmentArr.length() < 2) continue
            val startSec = segmentArr.optDouble(0, Double.NaN)
            val endSec = segmentArr.optDouble(1, Double.NaN)
            items.add(
                RawSegment(
                    cid = cid,
                    category = category,
                    uuid = uuid,
                    actionType = actionType,
                    startSec = startSec,
                    endSec = endSec,
                ),
            )
        }
        return normalizeSegments(items)
    }

    internal fun normalizeSegments(items: List<RawSegment>): List<Segment> {
        val out = ArrayList<Segment>(items.size)
        for (item in items) {
            if (!item.startSec.isFinite() || !item.endSec.isFinite()) continue
            val startMs = (item.startSec * 1000.0).toLong().coerceAtLeast(0L)
            val endMs = (item.endSec * 1000.0).toLong().coerceAtLeast(0L)
            val isPoi = isPoiSegment(category = item.category, actionType = item.actionType)
            if (isPoi) {
                if (endMs < startMs) continue
            } else if (endMs <= startMs) {
                continue
            }
            out.add(
                Segment(
                    cid = item.cid?.trim()?.toLongOrNull(),
                    startMs = startMs,
                    endMs = endMs,
                    category = item.category,
                    uuid = item.uuid,
                    actionType = item.actionType,
                ),
            )
        }
        return out
    }

    internal fun pickSegmentsForCid(segments: List<Segment>, cid: Long): List<Segment> {
        val exact = segments.filter { it.cid == cid }
        if (exact.isNotEmpty()) return exact

        val uniqueKnownCids = segments.mapNotNull { it.cid }.toSet()
        return when {
            uniqueKnownCids.isEmpty() -> segments
            uniqueKnownCids.size == 1 -> segments
            else -> emptyList()
        }
    }

    internal fun isPoiSegment(category: String?, actionType: String?): Boolean {
        val normalizedAction = actionType?.trim().orEmpty()
        if (normalizedAction.equals(ACTION_POI, ignoreCase = true)) return true
        return category?.trim().equals("poi_highlight", ignoreCase = true)
    }
}
