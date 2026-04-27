package blbl.cat3399.feature.my

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import blbl.cat3399.R
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.BangumiEpisode
import blbl.cat3399.core.model.BangumiSeasonDetail
import blbl.cat3399.core.model.VideoCard
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.BaseActivity
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.core.ui.ThemeColor
import blbl.cat3399.core.ui.cloneInUserScale
import blbl.cat3399.core.ui.smoothScrollToPositionStart
import blbl.cat3399.core.util.Format
import blbl.cat3399.core.util.pgcAccessBadgeTextOf
import blbl.cat3399.databinding.ActivityVideoDetailBinding
import blbl.cat3399.feature.player.PlayerActivity
import blbl.cat3399.feature.player.PlayerPlaylistItem
import blbl.cat3399.feature.player.PlayerPlaylistStore
import blbl.cat3399.feature.video.VideoDetailHeaderAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val EP_INDEX_ONLY_REGEX = Regex("^\\d+(?:\\.\\d+)?$")
private val EP_NUMBER_REGEX = Regex("\\d+(?:\\.\\d+)?")

class BangumiDetailActivity : BaseActivity() {
    private lateinit var binding: ActivityVideoDetailBinding
    private lateinit var headerAdapter: VideoDetailHeaderAdapter

    private var loadJob: Job? = null
    private var requestToken: Int = 0
    private var hasLoadedDetailOnce: Boolean = false

    private var resolvedSeasonId: Long? = null
    private var progressLastEpId: Long? = null
    private var progressLastTimeSec: Long? = null

    private val seasonIdArg: Long? by lazy { intent.getLongExtra(EXTRA_SEASON_ID, -1L).takeIf { it > 0L } }
    private val epIdArg: Long? by lazy { intent.getLongExtra(EXTRA_EP_ID, -1L).takeIf { it > 0L } }
    private val isDramaArg: Boolean by lazy { intent.getBooleanExtra(EXTRA_IS_DRAMA, false) }
    private val continueEpIdArg: Long? by lazy { intent.getLongExtra(EXTRA_CONTINUE_EP_ID, -1L).takeIf { it > 0L } ?: epIdArg }
    private val continueEpIndexArg: Int? by lazy { intent.getIntExtra(EXTRA_CONTINUE_EP_INDEX, -1).takeIf { it > 0 } }

    private var title: String? = null
    private var metaText: String? = null
    private var desc: String? = null
    private var coverUrl: String? = null
    private var accessBadgeText: String? = null

    private var isFollowed: Boolean? = null
    private var followActionJob: Job? = null

    private var episodeOrderReversed: Boolean = false
    private var extrasOrderReversed: Boolean = false
    private var pendingContinueEpIdHint: Long? = null
    private var pendingContinueEpIndexHint: Int? = null

    private var mainEpisodes: List<BangumiEpisode> = emptyList()
    private var extraEpisodes: List<BangumiEpisode> = emptyList()
    private var continueEpisode: BangumiEpisode? = null

    private var mainEpisodeCards: List<VideoCard> = emptyList()
    private var extrasCards: List<VideoCard> = emptyList()
    private var mainSelectedKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    finish()
                }
            },
        )

        if (seasonIdArg == null && epIdArg == null) {
            AppToast.show(this, "缺少 seasonId/epId")
            finish()
            return
        }

        episodeOrderReversed = BiliClient.prefs.pgcEpisodeOrderReversed
        pendingContinueEpIdHint = continueEpIdArg
        pendingContinueEpIndexHint = continueEpIndexArg

        showLoadingUi()
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)

        initUi()
        load(showRefreshing = true)
    }

    override fun onResume() {
        super.onResume()
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
        if (this::headerAdapter.isInitialized) headerAdapter.invalidateSizing()
        if (!hasLoadedDetailOnce) return
        if (binding.swipeRefresh.isRefreshing) return
        if (loadJob?.isActive == true) return
        load(showRefreshing = false)
    }

    private fun showLoadingUi() {
        val root =
            FrameLayout(this).apply {
                ThemeColor.applyBackground(
                    view = this,
                    attr = R.attr.blblPageBackdrop,
                    fallbackRes = R.color.blbl_bg,
                )
                addView(
                    ProgressBar(this@BangumiDetailActivity),
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        gravity = Gravity.CENTER
                    },
                )
            }
        setContentView(root)
    }

    private fun initUi() {
        binding = ActivityVideoDetailBinding.inflate(layoutInflater.cloneInUserScale(this))
        setContentView(binding.root)
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)

        binding.btnBack.setOnClickListener { finish() }

        headerAdapter =
            VideoDetailHeaderAdapter(
                onPlayClick = { playContinue() },
                onUpClick = { /* no-op */ },
                onTabClick = { /* no-op */ },
                onTagClick = { },
                onLikeClick = { /* no-op */ },
                onLikeLongPress = { /* no-op */ },
                onCoinClick = { /* no-op */ },
                onFavClick = { /* no-op */ },
                onSecondaryClick = { onFollowButtonClicked() },
                onPrimaryActionFocused = { smoothScrollHeaderToTop() },
                onSecondaryActionFocused = { smoothScrollHeaderToTop() },
                onPartsOrderClick = {
                    episodeOrderReversed = !episodeOrderReversed
                    BiliClient.prefs.pgcEpisodeOrderReversed = episodeOrderReversed
                    applyHeader(partsScrollToStart = true)
                    binding.recycler.post { headerAdapter.requestFocusPartsOrder() }
                },
                onSeasonOrderClick = {
                    extrasOrderReversed = !extrasOrderReversed
                    applyHeader(seasonScrollToStart = true)
                    binding.recycler.post { headerAdapter.requestFocusSeasonOrder() }
                },
                onPartCardClick = { card, _ ->
                    val startPositionMs =
                        if (card.epId != null && card.epId == progressLastEpId) {
                            progressLastTimeSec?.takeIf { it >= 5L }?.times(1000L)
                        } else {
                            null
                        }
                    playEpisodeCard(card, listKind = "main", startPositionMs = startPositionMs)
                },
                onSeasonCardClick = { card, _ ->
                    val startPositionMs =
                        if (card.epId != null && card.epId == progressLastEpId) {
                            progressLastTimeSec?.takeIf { it >= 5L }?.times(1000L)
                        } else {
                            null
                        }
                    playEpisodeCard(card, listKind = "extra", startPositionMs = startPositionMs)
                },
            )

        binding.recycler.layoutManager = LinearLayoutManager(this)
        (binding.recycler.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        binding.recycler.adapter = headerAdapter

        binding.swipeRefresh.setOnRefreshListener { load(showRefreshing = true) }

        applyHeader()
        binding.recycler.post { headerAdapter.requestFocusPlay() }
    }

    private fun smoothScrollHeaderToTop() {
        if (!this::binding.isInitialized) return
        if (!binding.recycler.canScrollVertically(-1)) return
        binding.recycler.smoothScrollToPositionStart(0)
    }

    private fun load(showRefreshing: Boolean) {
        val token = ++requestToken
        loadJob?.cancel()
        loadJob = null

        if (showRefreshing) binding.swipeRefresh.isRefreshing = true

        loadJob =
            lifecycleScope.launch {
                try {
                    val seasonId = seasonIdArg
                    val epId = epIdArg
                    if (seasonId == null && epId == null) error("缺少 seasonId/epId")
                    val detail =
                        withContext(Dispatchers.IO) {
                            if (seasonId != null) {
                                BiliApi.bangumiSeasonDetail(seasonId = seasonId)
                            } else {
                                BiliApi.bangumiSeasonDetailByEpId(epId = epId ?: 0L)
                            }
                        }
                    if (token != requestToken) return@launch

                    resolvedSeasonId = detail.seasonId.takeIf { it > 0L } ?: seasonId
                    applyDetail(detail)
                    applyHeader()
                    hasLoadedDetailOnce = true
                } catch (t: Throwable) {
                    if (t is CancellationException) return@launch
                    AppLog.e("BangumiDetail", "load failed seasonId=${seasonIdArg ?: -1L} epId=${epIdArg ?: -1L}", t)
                    AppToast.show(this@BangumiDetailActivity, t.message ?: "加载失败")
                } finally {
                    if (token == requestToken) binding.swipeRefresh.isRefreshing = false
                }
            }
    }

    private fun applyDetail(detail: BangumiSeasonDetail) {
        title = detail.title
        desc = detail.evaluate.orEmpty()
        coverUrl = detail.coverUrl
        progressLastEpId = detail.progressLastEpId
        progressLastTimeSec = detail.progressLastTimeSec
        accessBadgeText =
            pgcAccessBadgeTextOf(
                buildList {
                    addAll(detail.episodes.map { it.badge })
                    detail.extraSections.forEach { section -> addAll(section.episodes.map { it.badge }) }
                },
            )
        if (detail.isFollowed != null) {
            isFollowed = detail.isFollowed
        } else if (isFollowed == null) {
            isFollowed = false
        }

        metaText =
            buildList {
                detail.subtitle?.takeIf { it.isNotBlank() }?.let { add(it) }
                detail.ratingScore?.let { add(String.format("%.1f分", it)) }
                detail.views?.let { add("${Format.count(it)}次观看") }
                detail.danmaku?.let { add("${Format.count(it)}条弹幕") }
            }.joinToString(" | ")
                .trim()
                .takeIf { it.isNotBlank() }

        mainEpisodes = normalizeEpisodeOrder(detail.episodes)
        extraEpisodes = detail.extraSections.flatMap { it.episodes }

        val continueEpIdHint = pendingContinueEpIdHint
        val continueEpIndexHint = pendingContinueEpIndexHint
        continueEpisode =
            (continueEpIdHint ?: detail.progressLastEpId)?.let { id ->
                mainEpisodes.firstOrNull { it.epId == id }
            } ?: continueEpIndexHint?.let { idx ->
                mainEpisodes.firstOrNull { it.title.trim() == idx.toString() }
            }
        pendingContinueEpIdHint = null
        pendingContinueEpIndexHint = null

        mainEpisodeCards =
            mainEpisodes.mapIndexedNotNull { index, ep ->
                val bvid = ep.bvid?.trim().orEmpty()
                val cid = ep.cid
                if (bvid.isBlank() || cid == null || cid <= 0L) return@mapIndexedNotNull null
                bangumiEpToVideoCard(ep = ep, defaultIndex = index, sectionTitle = null)
            }

        extrasCards =
            detail.extraSections.flatMap { section ->
                val sectionTitle = section.title.trim().takeIf { it.isNotBlank() }
                section.episodes.mapIndexedNotNull { index, ep ->
                    val bvid = ep.bvid?.trim().orEmpty()
                    val cid = ep.cid
                    if (bvid.isBlank() || cid == null || cid <= 0L) return@mapIndexedNotNull null
                    bangumiEpToVideoCard(ep = ep, defaultIndex = index, sectionTitle = sectionTitle)
                }
            }

        mainSelectedKey =
            continueEpisode?.let { target ->
                mainEpisodeCards.firstOrNull { it.epId == target.epId }?.let(::cardStableKey)
            }
    }

    private fun applyHeader(
        partsScrollToStart: Boolean = false,
        seasonScrollToStart: Boolean = false,
    ) {
        if (!this::headerAdapter.isInitialized) return
        val displayEpisodeCards = if (episodeOrderReversed) mainEpisodeCards.asReversed() else mainEpisodeCards
        val displayExtrasCards = if (extrasOrderReversed) extrasCards.asReversed() else extrasCards

        val episodesHeader =
            mainEpisodeCards.size.takeIf { it > 0 }?.let { n ->
                "${getString(R.string.my_episode_section)}（$n）"
            }
        val extrasHeader =
            extrasCards.size.takeIf { it > 0 }?.let { n ->
                "花絮/预告（$n）"
            }

        headerAdapter.update(
            title = title,
            metaText = metaText,
            desc = desc,
            coverUrl = coverUrl,
            accessBadgeText = accessBadgeText,
            usePosterCover = true,
            upName = null,
            upAvatar = null,
            tabName = null,
            tags = emptyList(),
            primaryButtonText = getString(if (continueEpisode != null) R.string.my_btn_continue else R.string.my_btn_play),
            secondaryButtonText =
                if (isFollowed == true) {
                    if (isDramaArg) "已追剧" else "已追番"
                } else {
                    if (isDramaArg) "追剧" else "追番"
                },
            showActions = false,
            partsHeaderText = episodesHeader,
            partsCards = displayEpisodeCards,
            partsSelectedKey = mainSelectedKey,
            partsOrderReversed = episodeOrderReversed,
            partsAutoScrollToSelected = !partsScrollToStart,
            partsScrollToStart = partsScrollToStart,
            seasonHeaderText = extrasHeader,
            seasonCards = displayExtrasCards,
            seasonSelectedKey = null,
            seasonOrderReversed = extrasOrderReversed,
            seasonAutoScrollToSelected = !seasonScrollToStart,
            seasonScrollToStart = seasonScrollToStart,
            recommendHeaderText = null,
        )
    }

    private fun playContinue() {
        val picked = continueEpisode
        if (picked != null) {
            val card = mainEpisodeCards.firstOrNull { it.epId == picked.epId }
            if (card != null) {
                val startPositionMs =
                    if (picked.epId == progressLastEpId) {
                        progressLastTimeSec?.takeIf { it >= 5L }?.times(1000L)
                    } else {
                        null
                    }
                playEpisodeCard(card, listKind = "main", startPositionMs = startPositionMs)
                return
            }
        }
        val first = mainEpisodeCards.firstOrNull()
        if (first == null) {
            AppToast.show(this, "暂无可播放剧集")
            return
        }
        playEpisodeCard(first, listKind = "main")
    }

    private fun onFollowButtonClicked() {
        if (followActionJob?.isActive == true) return
        if (!BiliClient.cookies.hasSessData()) {
            AppToast.show(this, if (isDramaArg) "请先登录后再追剧" else "请先登录后再追番")
            return
        }
        val seasonId = resolvedSeasonId ?: seasonIdArg
        if (seasonId == null || seasonId <= 0L) {
            AppToast.show(this, "缺少 seasonId")
            return
        }
        val followed = isFollowed == true

        followActionJob =
            lifecycleScope.launch {
                try {
                    val res =
                        withContext(Dispatchers.IO) {
                            if (followed) {
                                BiliApi.pgcFollowDel(seasonId = seasonId)
                            } else {
                                BiliApi.pgcFollowAdd(seasonId = seasonId)
                            }
                        }
                    val nextFollowed = res.status?.let { it > 0 } ?: !followed
                    isFollowed = nextFollowed
                    applyHeader()
                    val fallbackToast =
                        when {
                            nextFollowed -> if (isDramaArg) "追剧成功" else "追番成功"
                            else -> if (isDramaArg) "已取消追剧" else "已取消追番"
                        }
                    AppToast.show(this@BangumiDetailActivity, res.toast ?: fallbackToast)
                } catch (t: Throwable) {
                    if (t is CancellationException) return@launch
                    AppToast.show(this@BangumiDetailActivity, t.message ?: "操作失败")
                } finally {
                    followActionJob = null
                }
            }
    }

    private fun playEpisodeCard(
        card: VideoCard,
        listKind: String,
        startPositionMs: Long? = null,
    ) {
        val seasonId = resolvedSeasonId ?: seasonIdArg
        if (seasonId == null || seasonId <= 0L) {
            AppToast.show(this, "缺少 seasonId")
            return
        }
        val bvid = card.bvid.trim()
        val cid = card.cid ?: -1L
        if (bvid.isBlank() || cid <= 0L) {
            AppToast.show(this, "缺少播放信息（bvid/cid）")
            return
        }

        val cards =
            when (listKind) {
                "extra" -> extrasCards
                else -> mainEpisodeCards
            }
        val idx = cards.indexOfFirst { it.epId == card.epId }.takeIf { it >= 0 } ?: 0
        val playlistItems =
            cards.map {
                PlayerPlaylistItem(
                    bvid = it.bvid,
                    cid = it.cid,
                    epId = it.epId,
                    aid = it.aid,
                    title = it.title,
                )
            }
        val token =
            PlayerPlaylistStore.put(
                items = playlistItems,
                index = idx,
                source = "Bangumi:$seasonId:$listKind",
                uiCards = cards,
            )
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_BVID, bvid)
                .putExtra(PlayerActivity.EXTRA_CID, cid)
                .putExtra(PlayerActivity.EXTRA_SEASON_ID, seasonId)
                .apply { card.epId?.let { putExtra(PlayerActivity.EXTRA_EP_ID, it) } }
                .apply { card.aid?.let { putExtra(PlayerActivity.EXTRA_AID, it) } }
                .apply {
                    startPositionMs?.takeIf { it >= 5_000L }?.let { putExtra(PlayerActivity.EXTRA_START_POSITION_MS, it) }
                }
                .putExtra(PlayerActivity.EXTRA_PLAYLIST_TOKEN, token)
                .putExtra(PlayerActivity.EXTRA_PLAYLIST_INDEX, idx),
        )
    }

    private fun bangumiEpToVideoCard(ep: BangumiEpisode, defaultIndex: Int, sectionTitle: String?): VideoCard {
        val rawTitle = ep.title.trim().takeIf { it.isNotBlank() } ?: "-"
        val episodeNumberText =
            when {
                EP_INDEX_ONLY_REGEX.matches(rawTitle) -> rawTitle
                else -> parseEpisodeNumber(rawTitle)?.let { formatEpisodeNumber(it) }
            } ?: parseEpisodeNumber(ep.longTitle)?.let { formatEpisodeNumber(it) }
                ?: (defaultIndex + 1).toString()

        val longTitle = ep.longTitle.trim().takeIf { it.isNotBlank() }
        val fallbackTitle =
            if (EP_INDEX_ONLY_REGEX.matches(rawTitle)) {
                "第${rawTitle}话"
            } else {
                rawTitle
            }
        val title = longTitle ?: fallbackTitle.takeIf { it.isNotBlank() } ?: "第${defaultIndex + 1}集"

        return VideoCard(
            bvid = ep.bvid.orEmpty(),
            cid = ep.cid,
            aid = ep.aid,
            epId = ep.epId,
            title = title,
            coverUrl = ep.coverUrl.orEmpty(),
            durationSec = 0,
            ownerName = "",
            ownerFace = null,
            ownerMid = null,
            view = null,
            danmaku = null,
            pubDate = null,
            pubDateText = null,
            coverLeftBottomText = episodeNumberText.takeIf { sectionTitle.isNullOrBlank() },
            accessBadgeText = pgcAccessBadgeTextOf(ep.badge),
        )
    }

    private fun normalizeEpisodeOrder(list: List<BangumiEpisode>): List<BangumiEpisode> {
        if (list.size <= 1) return list

        data class Entry(
            val episode: BangumiEpisode,
            val hasNumber: Boolean,
            val number: Double,
            val originalIndex: Int,
        )

        val entries =
            list.mapIndexed { index, ep ->
                val num =
                    parseEpisodeNumber(ep.title)
                        ?: parseEpisodeNumber(ep.longTitle)
                Entry(
                    episode = ep,
                    hasNumber = num != null,
                    number = num ?: 0.0,
                    originalIndex = index,
                )
            }

        // Keep non-numeric items grouped after numeric episodes, while preserving their relative order.
        return entries
            .sortedWith(compareBy<Entry>({ !it.hasNumber }, { it.number }, { it.originalIndex }))
            .map { it.episode }
    }

    private fun parseEpisodeNumber(raw: String?): Double? {
        val s = raw?.trim().orEmpty()
        if (s.isBlank()) return null
        s.toDoubleOrNull()?.let { return it }
        val match = EP_NUMBER_REGEX.find(s) ?: return null
        return match.value.toDoubleOrNull()
    }

    private fun formatEpisodeNumber(number: Double): String {
        val asLong = number.toLong()
        if (number == asLong.toDouble()) return asLong.toString()
        return number.toString()
    }

    private fun cardStableKey(card: VideoCard): String =
        buildString {
            append(card.bvid)
            append('|')
            append(card.cid ?: -1L)
            append('|')
            append(card.aid ?: -1L)
            append('|')
            append(card.epId ?: -1L)
            append('|')
            append(card.title)
        }

    companion object {
        const val EXTRA_SEASON_ID: String = "season_id"
        const val EXTRA_EP_ID: String = "ep_id"
        const val EXTRA_IS_DRAMA: String = "is_drama"
        const val EXTRA_CONTINUE_EP_ID: String = "continue_ep_id"
        const val EXTRA_CONTINUE_EP_INDEX: String = "continue_ep_index"
    }
}
