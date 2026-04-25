package blbl.cat3399.feature.player

import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import blbl.cat3399.R
import blbl.cat3399.core.api.video.VideoDetail
import blbl.cat3399.core.image.ImageLoader
import blbl.cat3399.core.image.ImageUrl
import blbl.cat3399.core.model.VideoCard
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.core.util.Format
import blbl.cat3399.feature.video.VideoCardAdapter

private data class PlayerInfoShelfPayload(
    val title: String,
    val cards: List<VideoCard>,
    val hint: String,
    val usesRecommendFallback: Boolean,
)

private data class PlayerInfoRecommendPayload(
    val cards: List<VideoCard>,
    val hint: String,
)

internal fun PlayerActivity.initPlayerInfoPanel() {
    binding.btnPlayerInfoUpProfile.setOnClickListener { openCurrentUpDetail() }
    binding.btnPlayerInfoFollow.setOnClickListener { onUpQuickFollowClicked() }
    binding.actionPlayerInfoLike.setOnClickListener { onLikeButtonClicked(showControls = false) }
    binding.actionPlayerInfoCoin.setOnClickListener { onCoinButtonClicked(showControls = false) }
    binding.actionPlayerInfoFav.setOnClickListener { onFavButtonClicked(showControls = false) }

    binding.recyclerPlayerInfoShelf.layoutManager =
        LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
    binding.recyclerPlayerInfoShelf.itemAnimator = null
    binding.recyclerPlayerInfoShelf.adapter =
        VideoCardAdapter(
            onClick = { _, pos -> onPlayerInfoShelfClicked(pos) },
            fixedItemWidthDimenRes = R.dimen.player_info_shelf_card_width,
            fixedItemMarginDimenRes = R.dimen.player_info_shelf_card_margin,
            isSelected = { _, pos -> isPlayerInfoShelfPositionSelected(pos) },
        )

    binding.recyclerPlayerInfoRecommend.layoutManager = LinearLayoutManager(this)
    binding.recyclerPlayerInfoRecommend.itemAnimator = null
    binding.recyclerPlayerInfoRecommend.adapter =
        PlayerInfoRecommendAdapter { card, _ ->
            playPlayerInfoRecommended(card)
        }

    refreshPlayerInfoPanelContent()
    syncPlayerInfoPanelVisibility()
}

internal fun PlayerActivity.resetPlayerInfoPanelState() {
    currentPlayerDesc = null
    currentPlayerViewCount = null
    currentPlayerDanmakuCount = null
    currentPlayerPubDateSec = null
    currentPlayerCommentCount = null
    currentPlayerLikeCount = null
    currentPlayerCoinCount = null
    currentPlayerFavCount = null
    playerInfoShelfUsesRecommendFallback = false
    refreshPlayerInfoPanelContent()
}

internal fun PlayerActivity.applyPlayerInfoVideoDetail(detail: VideoDetail) {
    currentPlayerDesc = detail.description?.trim()?.takeIf { it.isNotBlank() }
    currentPlayerViewCount = detail.stat.view
    currentPlayerDanmakuCount = detail.stat.danmaku
    currentPlayerPubDateSec = detail.pubDateSec
    currentPlayerCommentCount = detail.stat.reply
    currentPlayerLikeCount = detail.stat.like
    currentPlayerCoinCount = detail.stat.coin
    currentPlayerFavCount = detail.stat.favorite
    refreshPlayerInfoPanelContent()
}

internal fun PlayerActivity.syncPlayerInfoPanelVisibility() {
    val shouldShow =
        BiliClient.prefs.playerStyle == AppPrefs.PLAYER_STYLE_HD &&
            !isSettingsPanelVisible() &&
            !isCommentsPanelVisible()
    binding.playerInfoPanel.visibility = if (shouldShow) View.VISIBLE else View.GONE
}

internal fun PlayerActivity.refreshPlayerInfoPanelContent() {
    updatePlayerInfoUpUi()
    updatePlayerInfoActionUi()

    val title =
        currentMainTitle?.trim().orEmpty().ifBlank {
            binding.tvTitle.text?.toString()?.trim().orEmpty()
        }.ifBlank { "-" }
    binding.tvPlayerInfoTitle.text = title

    val stats =
        buildList {
            currentPlayerViewCount?.let { add("${Format.count(it)}播放") }
            currentPlayerDanmakuCount?.let { add("${Format.count(it)}弹幕") }
            currentPlayerPubDateSec?.let { pubDate ->
                val text = Format.pubDateText(pubDate).ifBlank { Format.timeText(pubDate) }
                if (text.isNotBlank()) add(text)
            }
        }
    binding.tvPlayerInfoStats.text = stats.joinToString(separator = "  ")
    binding.tvPlayerInfoStats.visibility = if (stats.isEmpty()) View.GONE else View.VISIBLE

    binding.tvPlayerInfoDesc.text = currentPlayerDesc ?: getString(R.string.player_info_desc_empty)
    binding.tabPlayerInfoComments.text =
        currentPlayerCommentCount?.let { "评论 ${Format.count(it)}" } ?: getString(R.string.player_btn_comments)

    val shelfPayload = buildPlayerInfoShelfPayload()
    playerInfoShelfUsesRecommendFallback = shelfPayload.usesRecommendFallback
    binding.tvPlayerInfoShelfTitle.text = shelfPayload.title
    (binding.recyclerPlayerInfoShelf.adapter as? VideoCardAdapter)?.submit(shelfPayload.cards)
    binding.recyclerPlayerInfoShelf.visibility = if (shelfPayload.cards.isNotEmpty()) View.VISIBLE else View.GONE
    binding.tvPlayerInfoShelfHint.text = shelfPayload.hint
    binding.tvPlayerInfoShelfHint.visibility = if (shelfPayload.cards.isEmpty()) View.VISIBLE else View.GONE

    val recommendPayload = buildPlayerInfoRecommendPayload()
    (binding.recyclerPlayerInfoRecommend.adapter as? PlayerInfoRecommendAdapter)?.submit(recommendPayload.cards)
    binding.recyclerPlayerInfoRecommend.visibility = if (recommendPayload.cards.isNotEmpty()) View.VISIBLE else View.GONE
    binding.tvPlayerInfoRecommendHint.text = recommendPayload.hint
    binding.tvPlayerInfoRecommendHint.visibility = if (recommendPayload.cards.isEmpty()) View.VISIBLE else View.GONE
}

internal fun PlayerActivity.updatePlayerInfoActionUi() {
    fun applyAction(iconView: android.widget.ImageView, textView: android.widget.TextView, active: Boolean, count: Long?, fallback: String) {
        val tint = if (active) R.color.blbl_blue else R.color.player_button_tint
        ImageViewCompat.setImageTintList(iconView, ContextCompat.getColorStateList(this, tint))
        textView.text = count?.let { Format.count(it) } ?: fallback
        textView.setTextColor(
            ContextCompat.getColor(
                this,
                if (active) R.color.blbl_blue else R.color.blbl_text,
            ),
        )
    }

    applyAction(binding.ivPlayerInfoLike, binding.tvPlayerInfoLike, active = actionLiked, count = currentPlayerLikeCount, fallback = "点赞")
    applyAction(binding.ivPlayerInfoCoin, binding.tvPlayerInfoCoin, active = actionCoinCount > 0, count = currentPlayerCoinCount, fallback = "投币")
    applyAction(binding.ivPlayerInfoFav, binding.tvPlayerInfoFav, active = actionFavored, count = currentPlayerFavCount, fallback = "收藏")
}

internal fun PlayerActivity.updatePlayerInfoUpUi() {
    val hasUp = currentUpMid > 0L
    binding.btnPlayerInfoUpProfile.visibility = if (hasUp) View.VISIBLE else View.GONE
    if (!hasUp) return

    binding.tvPlayerInfoUpName.text = currentUpName?.trim().orEmpty().ifBlank { "UP主" }
    binding.tvPlayerInfoUpMeta.text = "UP主"
    ImageLoader.loadInto(binding.ivPlayerInfoAvatar, ImageUrl.avatar(currentUpAvatar))

    val selfMid = BiliClient.cookies.getCookieValue("DedeUserID")?.trim()?.toLongOrNull()?.takeIf { it > 0L }
    val isSelf = selfMid != null && selfMid == currentUpMid
    binding.btnPlayerInfoFollow.visibility = if (isSelf) View.GONE else View.VISIBLE
    if (isSelf) return

    val isFollowed = currentUpFollowed == true
    binding.btnPlayerInfoFollow.isEnabled = !upFollowActionInFlight
    binding.btnPlayerInfoFollow.text =
        if (upFollowActionInFlight) {
            getString(R.string.player_loading)
        } else if (isFollowed) {
            getString(R.string.player_up_quick_followed)
        } else {
            getString(R.string.player_up_quick_follow)
        }
    binding.btnPlayerInfoFollow.setTextColor(
        ContextCompat.getColor(
            this,
            if (isFollowed) R.color.blbl_text_secondary else R.color.blbl_text,
        ),
    )
}

private fun PlayerActivity.buildPlayerInfoShelfPayload(): PlayerInfoShelfPayload {
    val partsCards = resolvePlayerInfoPartsCards()
    if (partsCards.isNotEmpty()) {
        return PlayerInfoShelfPayload(
            title = getString(R.string.player_list_tab_parts),
            cards = partsCards.take(10),
            hint = "",
            usesRecommendFallback = false,
        )
    }
    if (partsListFetchJob?.isActive == true) {
        return PlayerInfoShelfPayload(
            title = getString(R.string.player_list_tab_parts),
            cards = emptyList(),
            hint = getString(R.string.player_loading),
            usesRecommendFallback = false,
        )
    }

    val recommendCards = relatedCardsForInfoPanel()
    if (recommendCards.isNotEmpty()) {
        return PlayerInfoShelfPayload(
            title = "相关推荐",
            cards = recommendCards.take(6),
            hint = "",
            usesRecommendFallback = true,
        )
    }
    if (currentBvid.isNotBlank()) ensureRecommendCardsLoadedForAutoNext()
    return PlayerInfoShelfPayload(
        title = "相关推荐",
        cards = emptyList(),
        hint = if (relatedVideosFetchJob?.isActive == true) getString(R.string.player_loading) else getString(R.string.player_list_empty),
        usesRecommendFallback = true,
    )
}

private fun PlayerActivity.buildPlayerInfoRecommendPayload(): PlayerInfoRecommendPayload {
    val allRecommendCards = relatedCardsForInfoPanel()
    if (allRecommendCards.isEmpty()) {
        if (currentBvid.isNotBlank()) ensureRecommendCardsLoadedForAutoNext()
        return PlayerInfoRecommendPayload(
            cards = emptyList(),
            hint = if (relatedVideosFetchJob?.isActive == true) getString(R.string.player_loading) else getString(R.string.player_info_recommend_empty),
        )
    }
    val cards =
        if (playerInfoShelfUsesRecommendFallback) {
            allRecommendCards.drop(6)
        } else {
            allRecommendCards
        }
    return PlayerInfoRecommendPayload(
        cards = cards.take(8),
        hint = if (cards.isEmpty()) getString(R.string.player_info_recommend_empty) else "",
    )
}

private fun PlayerActivity.resolvePlayerInfoPartsCards(): List<VideoCard> {
    if (partsListItems.isEmpty()) return emptyList()
    if (partsListUiCards.isNotEmpty() && partsListUiCards.size == partsListItems.size) return partsListUiCards
    return partsListItems.mapIndexed { index, item ->
        VideoCard(
            bvid = item.bvid,
            cid = item.cid,
            aid = item.aid,
            epId = item.epId,
            title = item.title?.trim().takeUnless { it.isNullOrBlank() } ?: "视频 ${index + 1}",
            coverUrl = "",
            durationSec = 0,
            ownerName = "",
            ownerFace = null,
            ownerMid = null,
            view = null,
            danmaku = null,
            pubDate = null,
            pubDateText = null,
            seasonId = item.seasonId,
        )
    }
}

private fun PlayerActivity.relatedCardsForInfoPanel(): List<VideoCard> {
    val requestBvid = currentBvid.trim()
    if (requestBvid.isBlank()) return emptyList()
    return relatedVideosCache
        ?.takeIf { it.bvid == requestBvid }
        ?.items
        .orEmpty()
        .filterNot { it.bvid == requestBvid }
}

private fun PlayerActivity.onPlayerInfoShelfClicked(position: Int) {
    if (!playerInfoShelfUsesRecommendFallback) {
        if (position in partsListItems.indices) {
            playPartsListIndex(position)
        }
        return
    }
    val card = relatedCardsForInfoPanel().getOrNull(position) ?: return
    playPlayerInfoRecommended(card)
}

private fun PlayerActivity.playPlayerInfoRecommended(card: VideoCard) {
    if (card.bvid.isBlank() && (card.aid ?: 0L) <= 0L) return
    startPlayback(
        bvid = card.bvid,
        cidExtra = card.cid?.takeIf { it > 0 },
        epIdExtra = card.epId?.takeIf { it > 0 },
        aidExtra = card.aid?.takeIf { it > 0 },
        seasonIdExtra = card.seasonId?.takeIf { it > 0 },
        initialTitle = card.title.takeIf { it.isNotBlank() },
        startedFromList = PlayerVideoListKind.RECOMMEND,
    )
}

private fun PlayerActivity.isPlayerInfoShelfPositionSelected(position: Int): Boolean {
    return !playerInfoShelfUsesRecommendFallback && position == partsListIndex
}
