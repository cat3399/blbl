package blbl.cat3399.feature.player

import android.view.KeyEvent
import android.view.View
import androidx.lifecycle.lifecycleScope
import blbl.cat3399.databinding.ActivityPlayerBinding
import blbl.cat3399.databinding.IncludeVideoCommentImageViewerContentBinding
import blbl.cat3399.databinding.IncludeVideoCommentsPanelContentBinding
import blbl.cat3399.feature.video.comment.VideoCommentImageViewerController
import blbl.cat3399.feature.video.comment.VideoCommentImageViewerViews
import blbl.cat3399.feature.video.comment.VideoCommentsPanelController
import blbl.cat3399.feature.video.comment.VideoCommentsPanelViews

internal fun PlayerActivity.isSettingsPanelVisible(): Boolean = binding.settingsPanel.visibility == View.VISIBLE

internal fun PlayerActivity.isCommentsPanelVisible(): Boolean = binding.commentsPanel.visibility == View.VISIBLE

internal fun PlayerActivity.isCommentThreadVisible(): Boolean =
    videoCommentsController?.isThreadVisible() == true

internal fun PlayerActivity.isSidePanelVisible(): Boolean = isSettingsPanelVisible() || isCommentsPanelVisible()

internal fun PlayerActivity.isOverlayPanelVisible(): Boolean =
    isSidePanelVisible() || isBottomCardPanelVisible() || isSponsorSubmitPanelVisible()

internal fun ActivityPlayerBinding.videoCommentsPanelContent(): IncludeVideoCommentsPanelContentBinding =
    IncludeVideoCommentsPanelContentBinding.bind(root)

internal fun ActivityPlayerBinding.videoCommentImageViewerContent(): IncludeVideoCommentImageViewerContentBinding =
    IncludeVideoCommentImageViewerContentBinding.bind(commentImageViewer)

internal fun PlayerActivity.onOverlayPanelShown(openedFromMenuKey: Boolean) {
    when {
        openedFromMenuKey -> menuRevealedPanelSessionActive = true
        !isOverlayPanelVisible() -> menuRevealedPanelSessionActive = false
    }
}

internal fun PlayerActivity.onLastOverlayPanelDismissed() {
    if (isOverlayPanelVisible()) return
    menuRevealedPanelSessionActive = false
    setControlsVisible(false)
}

internal fun PlayerActivity.initSidePanels() {
    val imageViews = binding.videoCommentImageViewerContent()
    val commentViews = binding.videoCommentsPanelContent()
    videoCommentImageViewerController =
        VideoCommentImageViewerController(
            views =
                VideoCommentImageViewerViews(
                    container = binding.commentImageViewer,
                    image = imageViews.ivCommentImage,
                    previous = imageViews.ivCommentImagePrev,
                    next = imageViews.ivCommentImageNext,
                ),
            currentFocusProvider = { currentFocus },
            fallbackFocusProvider = {
                when {
                    videoCommentsController?.isThreadVisible() == true -> commentViews.recyclerCommentThread
                    isCommentsPanelVisible() -> commentViews.recyclerComments
                    else -> binding.btnComments
                }
            },
        )

    videoCommentsController =
        VideoCommentsPanelController(
            context = this,
            scope = lifecycleScope,
            views =
                VideoCommentsPanelViews(
                    sortRow = commentViews.rowCommentSort,
                    sortHot = commentViews.chipCommentSortHot,
                    sortNew = commentViews.chipCommentSortNew,
                    comments = commentViews.recyclerComments,
                    thread = commentViews.recyclerCommentThread,
                    hint = commentViews.tvCommentsHint,
                ),
            oidProvider = { currentAid?.takeIf { it > 0L } },
            upMidProvider = { currentUpMid },
            imageViewer = videoCommentImageViewerController,
            isActive = { !isFinishing && !isDestroyed && isCommentsPanelVisible() },
        )
}

internal fun PlayerActivity.toggleSettingsPanel() {
    if (isSettingsPanelVisible()) {
        hideSettingsPanel()
    } else {
        showSettingsPanel()
    }
}

internal fun PlayerActivity.showSettingsPanel(openedFromMenuKey: Boolean = false) {
    onOverlayPanelShown(openedFromMenuKey = openedFromMenuKey)
    hideBottomCardPanel(finalizeOverlaySession = false)
    if (isCommentsPanelVisible()) {
        videoCommentsController?.clearTransientUi()
    }
    binding.commentsPanel.visibility = View.GONE
    binding.settingsPanel.visibility = View.VISIBLE
    setControlsVisible(false)
    showSettingsRoot(focusKey = PlayerSettingKeys.RESOLUTION)
    syncPlayerInfoPanelVisibility()
}

internal fun PlayerActivity.hideSettingsPanel() {
    binding.settingsPanel.visibility = View.GONE
    syncPlayerInfoPanelVisibility()
    onLastOverlayPanelDismissed()
}

internal fun PlayerActivity.toggleCommentsPanel() {
    if (isCommentsPanelVisible()) {
        hideCommentsPanel()
    } else {
        showCommentsPanel()
    }
}

internal fun PlayerActivity.showCommentsPanel(openedFromMenuKey: Boolean = false) {
    onOverlayPanelShown(openedFromMenuKey = openedFromMenuKey)
    hideBottomCardPanel(finalizeOverlaySession = false)
    binding.settingsPanel.visibility = View.GONE
    binding.commentsPanel.visibility = View.VISIBLE
    setControlsVisible(false)
    videoCommentsController?.showRoot()
    videoCommentsController?.ensureLoaded()
    videoCommentsController?.focusRoot()
    syncPlayerInfoPanelVisibility()
}

internal fun PlayerActivity.hideCommentsPanel() {
    videoCommentsController?.clearTransientUi()
    binding.commentsPanel.visibility = View.GONE
    syncPlayerInfoPanelVisibility()
    onLastOverlayPanelDismissed()
}

internal fun PlayerActivity.onSidePanelBackPressed(): Boolean {
    if (isCommentsPanelVisible()) {
        if (videoCommentsController?.handleBack() == true) return true
        hideCommentsPanel()
        return true
    }
    if (isSettingsPanelVisible()) {
        if (backFromSettingsSubmenu()) {
            return true
        }
        hideSettingsPanel()
        return true
    }
    return false
}

internal fun PlayerActivity.closeCommentImageViewer(restoreFocus: Boolean = true) {
    videoCommentImageViewerController?.close(restoreFocus = restoreFocus)
}

internal fun PlayerActivity.isCommentImageViewerVisible(): Boolean =
    videoCommentImageViewerController?.isVisible() == true

internal fun PlayerActivity.dispatchCommentImageViewerKey(event: KeyEvent): Boolean =
    videoCommentImageViewerController?.dispatchKeyEvent(event) == true

internal fun PlayerActivity.ensureCommentsLoaded() {
    videoCommentsController?.ensureLoaded()
}
