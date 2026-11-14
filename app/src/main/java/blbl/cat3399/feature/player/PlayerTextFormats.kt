package blbl.cat3399.feature.player

import blbl.cat3399.feature.settings.SettingsText
import blbl.cat3399.feature.player.danmaku.DanmakuFontWeight
import blbl.cat3399.feature.player.danmaku.DanmakuLaneDensity
import org.json.JSONArray
import java.util.Locale

internal fun audioLabel(id: Int): String = SettingsText.audioText(id)

private val normalAudioOrder = intArrayOf(30216, 30232, 30280)

private fun normalAudioRank(id: Int): Int {
    val idx = normalAudioOrder.indexOf(id)
    return if (idx >= 0) idx else Int.MAX_VALUE
}

private fun firstAvailableAudioId(availableAudioIds: Collection<Int>, preferredOrder: IntArray): Int {
    for (id in preferredOrder) {
        if (availableAudioIds.contains(id)) return id
    }
    return 0
}

internal fun pickAudioIdByPreference(availableAudioIds: List<Int>, desiredAudioId: Int): Int {
    val available = availableAudioIds.filter { it > 0 }.distinct()
    if (available.isEmpty()) return 0
    if (desiredAudioId > 0 && available.contains(desiredAudioId)) return desiredAudioId

    if (desiredAudioId == 30251) {
        return firstAvailableAudioId(available, intArrayOf(30280, 30232, 30216, 30250)).takeIf { it > 0 } ?: available.first()
    }
    if (desiredAudioId == 30250) {
        return firstAvailableAudioId(available, intArrayOf(30280, 30232, 30216, 30251)).takeIf { it > 0 } ?: available.first()
    }

    val normalAudios = available.filter { normalAudioRank(it) != Int.MAX_VALUE }
    if (desiredAudioId in normalAudioOrder) {
        if (normalAudios.isNotEmpty()) {
            val desiredRank = normalAudioRank(desiredAudioId)
            val notAboveDesired = normalAudios.filter { normalAudioRank(it) <= desiredRank }
            return if (notAboveDesired.isNotEmpty()) {
                notAboveDesired.maxBy { normalAudioRank(it) }
            } else {
                normalAudios.minBy { normalAudioRank(it) }
            }
        }
        return firstAvailableAudioId(available, intArrayOf(30251, 30250)).takeIf { it > 0 } ?: available.first()
    }

    return firstAvailableAudioId(available, intArrayOf(30280, 30232, 30216, 30251, 30250)).takeIf { it > 0 } ?: available.first()
}

internal fun qnLabel(qn: Int): String = SettingsText.qnText(qn)

internal fun qnRank(qn: Int): Int {
    val order = intArrayOf(6, 16, 32, 64, 74, 80, 100, 112, 116, 120, 125, 126, 127, 129)
    val idx = order.indexOf(qn)
    return if (idx >= 0) idx else (order.size + qn)
}

internal fun pickQnByQualityOrder(availableQns: List<Int>, desiredQn: Int): Int {
    val available = availableQns.filter { it > 0 }.distinct()
    if (available.isEmpty()) return 0
    if (desiredQn <= 0) return available.maxBy { qnRank(it) }
    if (available.contains(desiredQn)) return desiredQn

    val desiredRank = qnRank(desiredQn)
    val notAboveDesired = available.filter { qnRank(it) <= desiredRank }
    if (notAboveDesired.isNotEmpty()) return notAboveDesired.maxBy { qnRank(it) }

    // All available qualities are above desiredQn: choose the lowest one to reduce decode risk.
    return available.minBy { qnRank(it) }
}

internal fun areaText(area: Float): String = SettingsText.areaText(area)

internal fun subtitleBottomPaddingText(fraction: Float): String = SettingsText.subtitleBottomPaddingText(fraction)

internal fun subtitleBackgroundOpacityText(opacity: Float): String = SettingsText.subtitleBackgroundOpacityText(opacity)

internal fun opacityText(opacity: Float): String = SettingsText.opacityText(opacity)

internal fun danmakuLaneDensityText(value: DanmakuLaneDensity): String = SettingsText.danmakuLaneDensityText(value.prefValue)

internal fun danmakuFontWeightText(value: DanmakuFontWeight): String = SettingsText.danmakuFontWeightText(value.prefValue)

internal fun aiLevelText(level: Int): String = SettingsText.aiLevelText(level)

internal fun normalizeUrl(url: String): String {
    val u = url.trim()
    return when {
        u.startsWith("//") -> "https:$u"
        u.startsWith("http://") || u.startsWith("https://") -> u
        else -> "https://$u"
    }
}

internal fun buildWebVtt(body: JSONArray): String {
    val sb = StringBuilder()
    sb.append("WEBVTT\n\n")
    for (i in 0 until body.length()) {
        val line = body.optJSONObject(i) ?: continue
        val from = line.optDouble("from", -1.0)
        val to = line.optDouble("to", -1.0)
        val content = line.optString("content", "").trim()
        if (from < 0 || to <= 0 || content.isBlank()) continue
        sb.append(formatVttTime(from)).append(" --> ").append(formatVttTime(to)).append('\n')
        sb.append(content.replace('\n', ' ')).append("\n\n")
    }
    return sb.toString()
}

private fun formatVttTime(sec: Double): String {
    val ms = (sec * 1000.0).toLong().coerceAtLeast(0L)
    val h = ms / 3_600_000
    val m = (ms % 3_600_000) / 60_000
    val s = (ms % 60_000) / 1000
    val milli = ms % 1000
    return String.format(Locale.US, "%02d:%02d:%02d.%03d", h, m, s, milli)
}

internal fun formatHms(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0L)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    // Keep the same style as other duration displays:
    // - < 1h: mm:ss (00:06 / 15:10)
    // - >= 1h: h:mm:ss (1:01:20)
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%02d:%02d", m, s)
    }
}

internal fun formatTransferBytes(bytes: Long): String {
    val b = bytes.coerceAtLeast(0L)
    if (b < 1024L) return "${b}B"
    val kb = b / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1fKB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.1fMB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2fGB", gb)
}
