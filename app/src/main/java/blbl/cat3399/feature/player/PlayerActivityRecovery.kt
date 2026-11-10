@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package blbl.cat3399.feature.player

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import blbl.cat3399.feature.player.engine.PlayerEngineKind
import blbl.cat3399.feature.player.recovery.PlaybackRecoveryRecord
import blbl.cat3399.feature.player.recovery.PlaybackRecoveryStore
import blbl.cat3399.feature.player.recovery.PlayerRecoveryController

internal const val RECOVERY_SAVED_RECORD = "playback_recovery_record"

internal fun PlayerActivity.restorePlaybackRecovery(savedState: Bundle?) {
    val requestedKey = PlaybackRecoveryRecord.vodKey(
        intent.getStringExtra(PlayerActivity.EXTRA_BVID).orEmpty(),
        intent.getLongExtra(PlayerActivity.EXTRA_AID, 0),
        intent.getLongExtra(PlayerActivity.EXTRA_CID, 0),
        intent.getLongExtra(PlayerActivity.EXTRA_EP_ID, 0),
    )
    val savedRecord = PlaybackRecoveryRecord.fromJson(savedState?.getString(RECOVERY_SAVED_RECORD))
    val restored = savedRecord ?: PlaybackRecoveryStore(this).load(requestedKey)
    recoveryEntryKey = restored?.entryKey ?: requestedKey
    if (restored == null || restored.live) return
    intent.putExtra(PlayerActivity.EXTRA_BVID, restored.bvid)
        .putExtra(PlayerActivity.EXTRA_CID, restored.cid)
        .putExtra(PlayerActivity.EXTRA_AID, restored.aid)
        .putExtra(PlayerActivity.EXTRA_EP_ID, restored.epId)
        .putExtra(PlayerActivity.EXTRA_SEASON_ID, restored.seasonId)
        .putExtra(PlayerActivity.EXTRA_TITLE, restored.title)
        .putExtra(PlayerActivity.EXTRA_COVER, restored.coverUrl)
    if (savedRecord != null || !intent.hasExtra(PlayerActivity.EXTRA_ENGINE_SWITCH_RESUME_POSITION_MS)) {
        intent.putExtra(PlayerActivity.EXTRA_ENGINE_SWITCH_RESUME_POSITION_MS, restored.positionMs)
        intent.putExtra(PlayerActivity.EXTRA_ENGINE_SWITCH_RESUME_PLAY_WHEN_READY, restored.playWhenReady)
    }
    if ((savedRecord != null || !intent.hasExtra(PlayerActivity.EXTRA_ENGINE_SWITCH_SESSION_JSON)) && restored.settingsJson.isNotBlank()) {
        intent.putExtra(PlayerActivity.EXTRA_ENGINE_SWITCH_SESSION_JSON, restored.settingsJson)
    }
}

internal fun PlayerActivity.initPlaybackRecovery() {
    playbackRecovery = PlayerRecoveryController(
        context = this,
        binding = binding,
        scope = lifecycleScope,
        surfaceView = {
            if (player?.kind == PlayerEngineKind.IjkPlayer) binding.ijkContainer.getChildAt(0)
            else binding.playerView.videoSurfaceView
        },
        showControls = { setControlsVisible(true) },
        onRecovered = { restartAutoHideTimer() },
        onRetry = { retryPlaybackRecovery() },
        onExit = { finish() },
    )
}

internal fun PlayerActivity.beginPlaybackRecovery(cid: Long?, title: String?, positionMs: Long?, playing: Boolean?) {
    val recovery = playbackRecovery ?: return
    val old = recovery.record
    val contentKey = PlaybackRecoveryRecord.vodKey(currentBvid, currentAid ?: 0, cid ?: 0, currentEpId ?: 0)
    val sameContent = old?.contentKey == contentKey
    val card = pageListUiCards.firstOrNull { it.bvid == currentBvid && (cid == null || it.cid == cid) }
    val fromIntent = currentBvid == intent.getStringExtra(PlayerActivity.EXTRA_BVID).orEmpty()
    recovery.begin(PlaybackRecoveryRecord(
        entryKey = recoveryEntryKey,
        bvid = currentBvid,
        cid = cid ?: 0,
        aid = currentAid ?: 0,
        epId = currentEpId ?: 0,
        seasonId = currentSeasonId ?: 0,
        title = title ?: old?.title?.takeIf { sameContent } ?: card?.title
            ?: intent.getStringExtra(PlayerActivity.EXTRA_TITLE)?.takeIf { fromIntent }.orEmpty(),
        coverUrl = old?.coverUrl?.takeIf { sameContent } ?: card?.coverUrl
            ?: intent.getStringExtra(PlayerActivity.EXTRA_COVER)?.takeIf { fromIntent }.orEmpty(),
        positionMs = positionMs ?: 0,
        playWhenReady = playing ?: true,
        settingsJson = session.toEngineSwitchJsonString(),
    ))
}

internal fun PlayerActivity.updatePlaybackRecoveryMetadata(coverUrl: String?) {
    val recovery = playbackRecovery ?: return
    val old = recovery.record ?: return
    recovery.updateContent(old.copy(
        bvid = currentBvid,
        cid = currentCid.coerceAtLeast(0),
        aid = currentAid ?: 0,
        title = currentMainTitle ?: old.title,
        coverUrl = coverUrl?.takeIf { it.isNotBlank() } ?: old.coverUrl,
    ))
}

internal fun PlayerActivity.retryPlaybackRecovery() {
    val saved = playbackRecovery?.record ?: return
    if (isFinishing || isDestroyed || playbackRecovery?.foreground != true) return
    pendingStartPositionMs = saved.positionMs
    pendingStartPlayWhenReady = saved.playWhenReady
    startPlayback(
        bvid = saved.bvid,
        cidExtra = saved.cid.takeIf { it > 0 },
        aidExtra = saved.aid.takeIf { it > 0 },
        epIdExtra = saved.epId.takeIf { it > 0 },
        seasonIdExtra = saved.seasonId.takeIf { it > 0 },
        initialTitle = saved.title,
    )
}
