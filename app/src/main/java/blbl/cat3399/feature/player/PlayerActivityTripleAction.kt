package blbl.cat3399.feature.player

import blbl.cat3399.core.log.AppLog
import androidx.lifecycle.lifecycleScope
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.AppToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val LIKE_BUTTON_HOLD_DURATION_MS = 2_000L

internal fun PlayerActivity.installLikeButtonHoldInteraction() {
    if (likeButtonHoldController != null) return
    likeButtonHoldController =
        HoldToTriggerController(
            view = binding.btnLike,
            progressHost = binding.btnLike,
            durationMs = LIKE_BUTTON_HOLD_DURATION_MS,
            onLongTrigger = {
                setControlsVisible(true)
                onLikeButtonLongPressed()
            },
        ).also { it.install() }
}

internal fun PlayerActivity.cancelLikeButtonHoldGesture(resetTriggered: Boolean = true) {
    likeButtonHoldController?.cancel(resetTriggered = resetTriggered)
}

private fun PlayerActivity.onLikeButtonLongPressed() {
    if (tripleActionJob?.isActive == true) return
    if (
        likeActionJob?.isActive == true ||
        coinActionJob?.isActive == true ||
        favDialogJob?.isActive == true ||
        favApplyJob?.isActive == true
    ) {
        AppToast.show(this, "操作进行中，请稍后")
        return
    }
    if (!BiliClient.cookies.hasSessData()) {
        AppToast.show(this, "请先登录后再一键三连")
        return
    }

    val requestBvid = currentBvid.trim().takeIf { it.isNotBlank() } ?: return
    val requestAid = currentAid?.takeIf { it > 0L }
    val selfMid = BiliClient.cookies.getCookieValue("DedeUserID")?.trim()?.toLongOrNull()?.takeIf { it > 0L }
    val initialState =
        ArchiveTripleActionState(
            liked = actionLiked,
            coinCount = actionCoinCount,
            favored = actionFavored,
        )

    if (initialState.isSatisfied) {
        AppToast.show(this, "已完成三连")
        return
    }

    val activity = this
    setControlsVisible(true)
    tripleActionJob =
        lifecycleScope.launch {
            try {
                val result =
                    executeArchiveTripleAction(
                        bvid = requestBvid,
                        aid = requestAid,
                        selfMid = selfMid,
                        initialState = initialState,
                        isStillValid = {
                            currentBvid.trim() == requestBvid &&
                                (requestAid == null || currentAid == requestAid)
                        },
                    )
                actionLiked = result.state.liked
                actionCoinCount = result.state.coinCount
                actionFavored = result.state.favored
                updateActionButtonsUi()
                AppToast.show(activity, result.toastMessage())
            } catch (t: Throwable) {
                if (t is CancellationException) return@launch
                AppLog.w("VideoAction", "triple action failed bvid=$requestBvid aid=$requestAid", t)
                AppToast.show(activity, t.userMessage(defaultMessage = "操作失败"))
            } finally {
                tripleActionJob = null
                updateActionButtonsUi()
            }
        }
}
