package blbl.cat3399.feature.video

import androidx.annotation.DrawableRes
import blbl.cat3399.R
import blbl.cat3399.core.model.VideoCard
import blbl.cat3399.core.prefs.AppPrefs

enum class VideoCardConfiguredLongPressAction {
    MANUAL,
    WATCH_LATER,
    OPEN_DETAIL,
    OPEN_UP,
    DISMISS,
    ;

    companion object {
        fun fromPref(value: String): VideoCardConfiguredLongPressAction {
            return when (AppPrefs.normalizeVideoCardLongPressAction(value)) {
                AppPrefs.VIDEO_CARD_LONG_PRESS_ACTION_WATCH_LATER -> WATCH_LATER
                AppPrefs.VIDEO_CARD_LONG_PRESS_ACTION_OPEN_DETAIL -> OPEN_DETAIL
                AppPrefs.VIDEO_CARD_LONG_PRESS_ACTION_OPEN_UP -> OPEN_UP
                AppPrefs.VIDEO_CARD_LONG_PRESS_ACTION_DISMISS -> DISMISS
                else -> MANUAL
            }
        }
    }
}

enum class VideoCardQuickActionId {
    WATCH_LATER,
    OPEN_DETAIL,
    OPEN_UP,
    DISMISS,
}

data class VideoCardQuickAction(
    val id: VideoCardQuickActionId,
    @DrawableRes val iconResId: Int,
    val contentDescription: CharSequence,
) {
    companion object {
        fun watchLater(label: CharSequence): VideoCardQuickAction =
            VideoCardQuickAction(
                id = VideoCardQuickActionId.WATCH_LATER,
                iconResId = R.drawable.ic_video_card_watch_later,
                contentDescription = label,
            )

        fun openDetail(label: CharSequence): VideoCardQuickAction =
            VideoCardQuickAction(
                id = VideoCardQuickActionId.OPEN_DETAIL,
                iconResId = R.drawable.ic_player_detail,
                contentDescription = label,
            )

        fun openUp(label: CharSequence): VideoCardQuickAction =
            VideoCardQuickAction(
                id = VideoCardQuickActionId.OPEN_UP,
                iconResId = R.drawable.ic_player_up,
                contentDescription = label,
            )

        fun dismiss(label: CharSequence): VideoCardQuickAction =
            VideoCardQuickAction(
                id = VideoCardQuickActionId.DISMISS,
                iconResId = R.drawable.ic_video_card_delete,
                contentDescription = label,
            )
    }
}

interface VideoCardActionDelegate {
    fun resolveLongPressAction(
        card: VideoCard,
        position: Int,
    ): VideoCardConfiguredLongPressAction

    fun manualActions(
        card: VideoCard,
        position: Int,
    ): List<VideoCardQuickAction>

    fun onActionSelected(
        card: VideoCard,
        position: Int,
        action: VideoCardQuickAction,
    )
}

internal fun VideoCard.hasVideoDetailIdentity(): Boolean =
    bvid.isNotBlank() || (aid ?: 0L) > 0L
