package blbl.cat3399.core.prefs

internal object PlayerPlaybackModes {
    val ordered = listOf(
        // Temporarily hidden from user-facing pickers, but keep runtime support for existing configs.
        // AppPrefs.PLAYER_PLAYBACK_MODE_PARTS_LIST_THEN_RECOMMEND,
        AppPrefs.PLAYER_PLAYBACK_MODE_PARTS_LIST,
        AppPrefs.PLAYER_PLAYBACK_MODE_PAGE_LIST,
        AppPrefs.PLAYER_PLAYBACK_MODE_RECOMMEND,
        AppPrefs.PLAYER_PLAYBACK_MODE_LOOP_ONE,
        AppPrefs.PLAYER_PLAYBACK_MODE_NONE,
        AppPrefs.PLAYER_PLAYBACK_MODE_EXIT,
    )

    fun normalize(code: String?): String {
        val raw = code?.trim().orEmpty()
        return when (raw) {
            AppPrefs.PLAYER_PLAYBACK_MODE_NONE,
            AppPrefs.PLAYER_PLAYBACK_MODE_LOOP_ONE,
            AppPrefs.PLAYER_PLAYBACK_MODE_EXIT,
            AppPrefs.PLAYER_PLAYBACK_MODE_PAGE_LIST,
            AppPrefs.PLAYER_PLAYBACK_MODE_PARTS_LIST,
            AppPrefs.PLAYER_PLAYBACK_MODE_PARTS_LIST_THEN_RECOMMEND,
            AppPrefs.PLAYER_PLAYBACK_MODE_RECOMMEND,
            -> raw

            else -> AppPrefs.PLAYER_PLAYBACK_MODE_NONE
        }
    }

    fun label(code: String): String {
        return when (normalize(code)) {
            AppPrefs.PLAYER_PLAYBACK_MODE_PARTS_LIST_THEN_RECOMMEND -> "播放完合集/分P后播放推荐视频"
            AppPrefs.PLAYER_PLAYBACK_MODE_PARTS_LIST -> "播放合集/分P视频"
            AppPrefs.PLAYER_PLAYBACK_MODE_PAGE_LIST -> "播放视频列表"
            AppPrefs.PLAYER_PLAYBACK_MODE_RECOMMEND -> "播放推荐视频"
            AppPrefs.PLAYER_PLAYBACK_MODE_LOOP_ONE -> "循环该视频"
            AppPrefs.PLAYER_PLAYBACK_MODE_EXIT -> "退出播放器"
            else -> "播完后停留"
        }
    }
}
