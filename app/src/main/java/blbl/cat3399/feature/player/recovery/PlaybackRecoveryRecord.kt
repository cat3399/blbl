package blbl.cat3399.feature.player.recovery

import android.content.Context
import org.json.JSONObject

internal data class PlaybackRecoveryRecord(
    val entryKey: String,
    val live: Boolean = false,
    val bvid: String = "",
    val cid: Long = 0,
    val aid: Long = 0,
    val epId: Long = 0,
    val seasonId: Long = 0,
    val roomId: Long = 0,
    val title: String = "",
    val coverUrl: String = "",
    val positionMs: Long = 0,
    val playWhenReady: Boolean = true,
    val settingsJson: String = "",
) {
    val contentKey: String
        get() = if (live) "live:$roomId" else vodKey(bvid, aid, cid, epId)

    fun toJson(): String = JSONObject().apply {
        put("entryKey", entryKey)
        put("live", live)
        put("bvid", bvid)
        put("cid", cid)
        put("aid", aid)
        put("epId", epId)
        put("seasonId", seasonId)
        put("roomId", roomId)
        put("title", title)
        put("coverUrl", coverUrl)
        put("positionMs", positionMs.coerceAtLeast(0))
        put("playWhenReady", playWhenReady)
        put("settingsJson", settingsJson)
    }.toString()

    companion object {
        fun vodKey(bvid: String, aid: Long, cid: Long, epId: Long): String =
            "vod:${bvid.trim().ifBlank { "av$aid" }}:${cid.coerceAtLeast(0)}:${epId.coerceAtLeast(0)}"

        fun fromJson(raw: String?): PlaybackRecoveryRecord? = runCatching {
            val obj = JSONObject(raw ?: return null)
            PlaybackRecoveryRecord(
                entryKey = obj.getString("entryKey"),
                live = obj.optBoolean("live"),
                bvid = obj.optString("bvid"),
                cid = obj.optLong("cid"),
                aid = obj.optLong("aid"),
                epId = obj.optLong("epId"),
                seasonId = obj.optLong("seasonId"),
                roomId = obj.optLong("roomId"),
                title = obj.optString("title"),
                coverUrl = obj.optString("coverUrl"),
                positionMs = obj.optLong("positionMs").coerceAtLeast(0),
                playWhenReady = obj.optBoolean("playWhenReady", true),
                settingsJson = obj.optString("settingsJson"),
            ).takeIf { if (it.live) it.roomId > 0 else it.bvid.isNotBlank() || it.aid > 0 }
        }.getOrNull()
    }
}

internal class PlaybackRecoveryStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("playback_recovery", Context.MODE_PRIVATE)

    fun load(entryKey: String): PlaybackRecoveryRecord? =
        PlaybackRecoveryRecord.fromJson(prefs.getString("record", null))
            ?.takeIf { it.entryKey == entryKey || it.contentKey == entryKey }

    fun save(owner: String, record: PlaybackRecoveryRecord) {
        prefs.edit().putString("owner", owner).putString("record", record.toJson()).apply()
    }

    fun clear(owner: String) {
        if (prefs.getString("owner", null) == owner) prefs.edit().clear().apply()
    }
}
