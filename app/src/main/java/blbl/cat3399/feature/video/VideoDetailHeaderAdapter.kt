package blbl.cat3399.feature.video

import android.text.Layout
import android.text.StaticLayout
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.core.model.VideoTag
import blbl.cat3399.core.image.ImageLoader
import blbl.cat3399.core.image.ImageUrl
import blbl.cat3399.core.model.VideoCard
import blbl.cat3399.core.ui.cloneInUserScale
import blbl.cat3399.databinding.ItemVideoDetailHeaderBinding
import blbl.cat3399.feature.player.HoldToTriggerController
import com.google.android.material.chip.Chip
import java.lang.ref.WeakReference

private const val DESC_COLLAPSED_TEXT_LINES = 4

class VideoDetailHeaderAdapter(
    private val onPlayClick: () -> Unit,
    private val onUpClick: () -> Unit,
    private val onTabClick: (tabName: String) -> Unit,
    private val onTagClick: (tag: VideoTag) -> Unit,
    private val onLikeClick: () -> Unit,
    private val onLikeLongPress: () -> Unit,
    private val onCoinClick: () -> Unit,
    private val onFavClick: () -> Unit,
    private val onSecondaryClick: () -> Unit,
    private val onUpCardFocused: (() -> Unit)? = null,
    private val onPrimaryActionFocused: (() -> Unit)? = null,
    private val onSecondaryActionFocused: (() -> Unit)? = null,
    private val onPartsOrderClick: () -> Unit,
    private val onSeasonOrderClick: () -> Unit,
    private val onPartCardClick: (card: VideoCard, index: Int) -> Unit,
    private val onSeasonCardClick: (card: VideoCard, index: Int) -> Unit,
) : RecyclerView.Adapter<VideoDetailHeaderAdapter.Vh>() {
    private var holderRef: WeakReference<Vh>? = null

    private var title: String? = null
    private var metaText: String? = null
    private var desc: String? = null
    private var descKey: String? = null
    private var descExpanded: Boolean = false
    private var coverUrl: String? = null
    private var accessBadgeText: String? = null
    private var usePosterCover: Boolean = false
    private var upName: String? = null
    private var upAvatar: String? = null
    private var tabName: String? = null
    private var tags: List<VideoTag> = emptyList()

    private var primaryButtonText: String? = null
    private var secondaryButtonText: String? = null
    private var showActions: Boolean = true
    private var actionLiked: Boolean = false
    private var actionCoinCount: Int = 0
    private var actionFavored: Boolean = false

    private var partsHeaderText: String? = null
    private var partsCards: List<VideoCard> = emptyList()
    private var partsSelectedKey: String? = null
    private var partsOrderReversed: Boolean = false
    private var partsAutoScrollToSelected: Boolean = true
    private var partsScrollToStart: Boolean = false

    private var seasonHeaderText: String? = null
    private var seasonCards: List<VideoCard> = emptyList()
    private var seasonSelectedKey: String? = null
    private var seasonOrderReversed: Boolean = false
    private var seasonAutoScrollToSelected: Boolean = true
    private var seasonScrollToStart: Boolean = false

    private var recommendHeaderText: String? = null

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = 1L

    override fun getItemCount(): Int = 1

    fun requestFocusPlay(): Boolean = holderRef?.get()?.binding?.btnPlay?.requestFocus() == true

    fun requestFocusLike(): Boolean = holderRef?.get()?.binding?.btnLike?.requestFocus() == true

    fun requestFocusCoin(): Boolean = holderRef?.get()?.binding?.btnCoin?.requestFocus() == true

    fun requestFocusFav(): Boolean = holderRef?.get()?.binding?.btnFav?.requestFocus() == true

    fun requestFocusPartsOrder(): Boolean = holderRef?.get()?.binding?.btnPartsOrder?.requestFocus() == true

    fun requestFocusSeasonOrder(): Boolean = holderRef?.get()?.binding?.btnSeasonOrder?.requestFocus() == true

    fun invalidateSizing() {
        notifyItemChanged(0)
    }

    fun update(
        title: String?,
        metaText: String?,
        desc: String?,
        coverUrl: String?,
        accessBadgeText: String? = null,
        usePosterCover: Boolean,
        upName: String?,
        upAvatar: String?,
        tabName: String?,
        tags: List<VideoTag>,
        primaryButtonText: String?,
        secondaryButtonText: String?,
        showActions: Boolean,
        actionLiked: Boolean = false,
        actionCoinCount: Int = 0,
        actionFavored: Boolean = false,
        partsHeaderText: String?,
        partsCards: List<VideoCard>,
        partsSelectedKey: String?,
        partsOrderReversed: Boolean,
        partsAutoScrollToSelected: Boolean = true,
        partsScrollToStart: Boolean = false,
        seasonHeaderText: String?,
        seasonCards: List<VideoCard>,
        seasonSelectedKey: String?,
        seasonOrderReversed: Boolean,
        seasonAutoScrollToSelected: Boolean = true,
        seasonScrollToStart: Boolean = false,
        recommendHeaderText: String?,
    ) {
        val nextDescKey = desc?.trim()?.takeIf { it.isNotBlank() }
        if (nextDescKey != descKey) {
            descKey = nextDescKey
            descExpanded = false
        }

        this.title = title
        this.metaText = metaText
        this.desc = desc
        this.coverUrl = coverUrl
        this.accessBadgeText = accessBadgeText
        this.usePosterCover = usePosterCover
        this.upName = upName
        this.upAvatar = upAvatar
        this.tabName = tabName
        this.tags = tags

        this.primaryButtonText = primaryButtonText
        this.secondaryButtonText = secondaryButtonText
        this.showActions = showActions
        this.actionLiked = actionLiked
        this.actionCoinCount = actionCoinCount
        this.actionFavored = actionFavored

        this.partsHeaderText = partsHeaderText
        this.partsCards = partsCards
        this.partsSelectedKey = partsSelectedKey
        this.partsOrderReversed = partsOrderReversed
        this.partsAutoScrollToSelected = partsAutoScrollToSelected
        this.partsScrollToStart = partsScrollToStart

        this.seasonHeaderText = seasonHeaderText
        this.seasonCards = seasonCards
        this.seasonSelectedKey = seasonSelectedKey
        this.seasonOrderReversed = seasonOrderReversed
        this.seasonAutoScrollToSelected = seasonAutoScrollToSelected
        this.seasonScrollToStart = seasonScrollToStart

        this.recommendHeaderText = recommendHeaderText
        notifyItemChanged(0)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
        val binding =
            ItemVideoDetailHeaderBinding.inflate(
                LayoutInflater.from(parent.context).cloneInUserScale(parent.context),
                parent,
                false,
            )
        return Vh(
            binding = binding,
            onPlayClick = onPlayClick,
            onUpClick = onUpClick,
            onTabClick = onTabClick,
            onTagClick = onTagClick,
            onLikeClick = onLikeClick,
            onLikeLongPress = onLikeLongPress,
            onCoinClick = onCoinClick,
            onFavClick = onFavClick,
            onSecondaryClick = onSecondaryClick,
            onUpCardFocused = onUpCardFocused,
            onPrimaryActionFocused = onPrimaryActionFocused,
            onSecondaryActionFocused = onSecondaryActionFocused,
            onPartsOrderClick = onPartsOrderClick,
            onSeasonOrderClick = onSeasonOrderClick,
            onPartCardClick = onPartCardClick,
            onSeasonCardClick = onSeasonCardClick,
            onDescExpandClick = {
                if (descExpanded) {
                    false
                } else {
                    descExpanded = true
                    true
                }
            },
        )
    }

    override fun onBindViewHolder(holder: Vh, position: Int) {
        holder.bind(
            title = title,
            metaText = metaText,
            desc = desc,
            descExpanded = descExpanded,
            coverUrl = coverUrl,
            accessBadgeText = accessBadgeText,
            usePosterCover = usePosterCover,
            upName = upName,
            upAvatar = upAvatar,
            tabName = tabName,
            tags = tags,
            primaryButtonText = primaryButtonText,
            secondaryButtonText = secondaryButtonText,
            showActions = showActions,
            actionLiked = actionLiked,
            actionCoinCount = actionCoinCount,
            actionFavored = actionFavored,
            partsHeaderText = partsHeaderText,
            partsCards = partsCards,
            partsSelectedKey = partsSelectedKey,
            partsOrderReversed = partsOrderReversed,
            partsAutoScrollToSelected = partsAutoScrollToSelected,
            partsScrollToStart = partsScrollToStart,
            seasonHeaderText = seasonHeaderText,
            seasonCards = seasonCards,
            seasonSelectedKey = seasonSelectedKey,
            seasonOrderReversed = seasonOrderReversed,
            seasonAutoScrollToSelected = seasonAutoScrollToSelected,
            seasonScrollToStart = seasonScrollToStart,
            recommendHeaderText = recommendHeaderText,
        )
    }

    override fun onViewAttachedToWindow(holder: Vh) {
        super.onViewAttachedToWindow(holder)
        holderRef = WeakReference(holder)
    }

    override fun onViewDetachedFromWindow(holder: Vh) {
        val current = holderRef?.get()
        if (current === holder) holderRef = null
        holder.cancelTransientUi()
        super.onViewDetachedFromWindow(holder)
    }

    class Vh(
        val binding: ItemVideoDetailHeaderBinding,
        private val onPlayClick: () -> Unit,
        private val onUpClick: () -> Unit,
        private val onTabClick: (tabName: String) -> Unit,
        private val onTagClick: (tag: VideoTag) -> Unit,
        private val onLikeClick: () -> Unit,
        private val onLikeLongPress: () -> Unit,
        private val onCoinClick: () -> Unit,
        private val onFavClick: () -> Unit,
        private val onSecondaryClick: () -> Unit,
        private val onUpCardFocused: (() -> Unit)?,
        private val onPrimaryActionFocused: (() -> Unit)?,
        private val onSecondaryActionFocused: (() -> Unit)?,
        private val onPartsOrderClick: () -> Unit,
        private val onSeasonOrderClick: () -> Unit,
        private val onPartCardClick: (card: VideoCard, index: Int) -> Unit,
        private val onSeasonCardClick: (card: VideoCard, index: Int) -> Unit,
        private val onDescExpandClick: () -> Boolean,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var partsSelectedKey: String? = null
        private var seasonSelectedKey: String? = null
        private var lastTagsKey: String? = null
        private var lastDescLayoutKey: String? = null

        private val partsAdapter =
            VideoCardAdapter(
                onClick = { card, index -> onPartCardClick(card, index) },
                onLongClick = null,
                fixedItemWidthDimenRes = blbl.cat3399.R.dimen.video_detail_section_card_width,
                fixedItemMarginDimenRes = null,
                stableIdKey = ::cardStableKey,
                isSelected = { card, _ -> cardStableKey(card) == partsSelectedKey },
            )

        private val seasonAdapter =
            VideoCardAdapter(
                onClick = { card, index -> onSeasonCardClick(card, index) },
                onLongClick = null,
                fixedItemWidthDimenRes = blbl.cat3399.R.dimen.video_detail_section_card_width,
                fixedItemMarginDimenRes = null,
                stableIdKey = ::cardStableKey,
                isSelected = { card, _ -> cardStableKey(card) == seasonSelectedKey },
            )

        private var lastPartsAutoScrollKey: String? = null
        private var lastSeasonAutoScrollKey: String? = null
        private val likeHoldController =
            HoldToTriggerController(
                view = binding.btnLike,
                progressHost = binding.btnLike,
                durationMs = 2_000L,
                onLongTrigger = onLikeLongPress,
            )

        init {
            binding.btnPlay.setOnClickListener { onPlayClick() }
            binding.cardUp.setOnClickListener { onUpClick() }
            binding.cardTab.setOnClickListener {
                val tab = (binding.cardTab.tag as? String)?.trim().orEmpty()
                if (tab.isNotBlank()) onTabClick(tab)
            }
            binding.layoutDesc.setOnClickListener {
                if (onDescExpandClick()) {
                    applyDescExpanded(expanded = true)
                    binding.layoutDesc.requestFocus()
                    binding.root.requestLayout()
                }
            }

            binding.cardUp.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onUpCardFocused?.invoke()
            }
            binding.btnPlay.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onPrimaryActionFocused?.invoke()
            }
            binding.btnSecondary.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onSecondaryActionFocused?.invoke()
            }

            likeHoldController.install()
            binding.btnLike.setOnClickListener { onLikeClick() }
            binding.btnCoin.setOnClickListener { onCoinClick() }
            binding.btnFav.setOnClickListener { onFavClick() }
            binding.btnSecondary.setOnClickListener { onSecondaryClick() }

            binding.btnPartsOrder.setOnClickListener { onPartsOrderClick() }
            binding.btnSeasonOrder.setOnClickListener { onSeasonOrderClick() }

            binding.recyclerParts.layoutManager =
                LinearLayoutManager(binding.root.context, LinearLayoutManager.HORIZONTAL, false)
            binding.recyclerParts.itemAnimator = null
            binding.recyclerParts.adapter = partsAdapter

            binding.recyclerSeason.layoutManager =
                LinearLayoutManager(binding.root.context, LinearLayoutManager.HORIZONTAL, false)
            binding.recyclerSeason.itemAnimator = null
            binding.recyclerSeason.adapter = seasonAdapter
        }

        fun bind(
            title: String?,
            metaText: String?,
            desc: String?,
            descExpanded: Boolean,
            coverUrl: String?,
            accessBadgeText: String?,
            usePosterCover: Boolean,
            upName: String?,
            upAvatar: String?,
            tabName: String?,
            tags: List<VideoTag>,
            primaryButtonText: String?,
            secondaryButtonText: String?,
            showActions: Boolean,
            actionLiked: Boolean,
            actionCoinCount: Int,
            actionFavored: Boolean,
            partsHeaderText: String?,
            partsCards: List<VideoCard>,
            partsSelectedKey: String?,
            partsOrderReversed: Boolean,
            partsAutoScrollToSelected: Boolean,
            partsScrollToStart: Boolean,
            seasonHeaderText: String?,
            seasonCards: List<VideoCard>,
            seasonSelectedKey: String?,
            seasonOrderReversed: Boolean,
            seasonAutoScrollToSelected: Boolean,
            seasonScrollToStart: Boolean,
            recommendHeaderText: String?,
        ) {
            binding.tvTitle.text = title?.trim().takeIf { !it.isNullOrBlank() } ?: "-"

            val safeMeta = metaText?.trim().takeIf { !it.isNullOrBlank() }
            binding.tvMeta.isVisible = safeMeta != null
            binding.tvMeta.text = safeMeta.orEmpty()

            applyCoverContainerRatio(usePosterCover)

            val safeCover = coverUrl?.trim().takeIf { !it.isNullOrBlank() }
            binding.ivCoverPoster.isVisible = false
            binding.ivCover.alpha = 1f
            if (safeCover != null) {
                val loadUrl =
                    if (usePosterCover) {
                        ImageUrl.poster(safeCover)
                    } else {
                        ImageUrl.cover(safeCover)
                    }
                ImageLoader.loadInto(binding.ivCover, loadUrl)
                binding.ivCoverPoster.setImageDrawable(null)
            } else {
                binding.ivCover.setImageDrawable(null)
                binding.ivCoverPoster.setImageDrawable(null)
            }

            val safeAccessBadgeText = accessBadgeText?.trim().takeIf { !it.isNullOrBlank() }
            binding.tvAccessBadgeText.isVisible = safeAccessBadgeText != null
            binding.tvAccessBadgeText.text = safeAccessBadgeText.orEmpty()

            val safeUpName = upName?.trim().takeIf { !it.isNullOrBlank() }
            binding.cardUp.isVisible = safeUpName != null
            if (safeUpName != null) {
                binding.tvUpName.text = safeUpName
                ImageLoader.loadInto(binding.ivUpAvatar, ImageUrl.avatar(upAvatar))
            }

            bindDesc(desc = desc, expanded = descExpanded)

            bindTags(tags)

            val safeTab = tabName?.trim().takeIf { !it.isNullOrBlank() }
            binding.cardTab.isVisible = safeTab != null
            binding.cardTab.tag = safeTab
            binding.tvTab.text = safeTab?.let { "分区：$it" }.orEmpty()

            binding.btnPlay.text = primaryButtonText?.trim().takeIf { !it.isNullOrBlank() } ?: "播放"

            val safeSecondary = secondaryButtonText?.trim().takeIf { !it.isNullOrBlank() }
            binding.btnSecondary.isVisible = safeSecondary != null
            binding.btnSecondary.text = safeSecondary.orEmpty()

            binding.btnLike.isVisible = showActions
            binding.btnCoin.isVisible = showActions
            binding.btnFav.isVisible = showActions
            if (showActions) {
                val activeColor = ContextCompat.getColorStateList(binding.root.context, blbl.cat3399.R.color.blbl_blue)
                val inactiveColor = ContextCompat.getColorStateList(binding.root.context, blbl.cat3399.R.color.blbl_text_secondary)
                binding.ivLike.imageTintList = if (actionLiked) activeColor else inactiveColor
                binding.ivCoin.imageTintList = if (actionCoinCount > 0) activeColor else inactiveColor
                binding.ivFav.imageTintList = if (actionFavored) activeColor else inactiveColor
            } else {
                likeHoldController.cancel(resetTriggered = true)
            }

            val safePartsHeader = partsHeaderText?.trim()?.takeIf { it.isNotBlank() }
            val showParts = safePartsHeader != null && partsCards.isNotEmpty()
            binding.tvPartsHeader.isVisible = showParts
            binding.btnPartsOrder.isVisible = showParts && partsCards.size > 1
            binding.recyclerParts.isVisible = showParts
            if (showParts) {
                binding.tvPartsHeader.text = safePartsHeader
                binding.tvPartsOrder.text =
                    if (partsCards.size > 1) {
                        binding.root.context.getString(
                            if (partsOrderReversed) {
                                blbl.cat3399.R.string.my_episode_order_desc
                            } else {
                                blbl.cat3399.R.string.my_episode_order_asc
                            },
                        )
                    } else {
                        ""
                    }
                this.partsSelectedKey = partsSelectedKey
                partsAdapter.submit(partsCards)
                when {
                    partsScrollToStart -> {
                        lastPartsAutoScrollKey = null
                        scrollPartsToStart()
                    }
                    partsAutoScrollToSelected -> maybeAutoScrollParts(partsCards, partsSelectedKey)
                    else -> lastPartsAutoScrollKey = null
                }
            } else {
                binding.tvPartsOrder.text = ""
                this.partsSelectedKey = null
                partsAdapter.submit(emptyList())
                lastPartsAutoScrollKey = null
            }

            val safeSeasonHeader = seasonHeaderText?.trim()?.takeIf { it.isNotBlank() }
            val showSeason = safeSeasonHeader != null && seasonCards.isNotEmpty()
            binding.tvSeasonHeader.isVisible = showSeason
            binding.btnSeasonOrder.isVisible = showSeason && seasonCards.size > 1
            binding.recyclerSeason.isVisible = showSeason
            if (showSeason) {
                binding.tvSeasonHeader.text = safeSeasonHeader
                binding.tvSeasonOrder.text =
                    if (seasonCards.size > 1) {
                        binding.root.context.getString(
                            if (seasonOrderReversed) {
                                blbl.cat3399.R.string.my_episode_order_desc
                            } else {
                                blbl.cat3399.R.string.my_episode_order_asc
                            },
                        )
                    } else {
                        ""
                    }
                this.seasonSelectedKey = seasonSelectedKey
                seasonAdapter.submit(seasonCards)
                when {
                    seasonScrollToStart -> {
                        lastSeasonAutoScrollKey = null
                        scrollSeasonToStart()
                    }
                    seasonAutoScrollToSelected -> maybeAutoScrollSeason(seasonCards, seasonSelectedKey)
                    else -> lastSeasonAutoScrollKey = null
                }
            } else {
                binding.tvSeasonOrder.text = ""
                this.seasonSelectedKey = null
                seasonAdapter.submit(emptyList())
                lastSeasonAutoScrollKey = null
            }

            val safeRecommend = recommendHeaderText?.trim()?.takeIf { it.isNotBlank() }
            binding.tvRecommendHeader.isVisible = safeRecommend != null
            binding.tvRecommendHeader.text = safeRecommend.orEmpty()

            // Nested horizontal lists can change their measured height after adapter updates;
            // ensure the header item gets re-measured to avoid overlap with following items.
            binding.root.requestLayout()
        }

        fun cancelTransientUi() {
            likeHoldController.cancel(resetTriggered = true)
        }

        private fun bindDesc(desc: String?, expanded: Boolean) {
            val safeDesc = desc?.trim().takeIf { !it.isNullOrBlank() }
            binding.tvDesc.text = safeDesc ?: "暂无简介"
            lastDescLayoutKey = safeDesc
            applyDescFocusable(expandable = false)
            applyDescExpanded(expanded = false, expandable = false)
            if (safeDesc == null) return

            binding.layoutDesc.post {
                if (lastDescLayoutKey != safeDesc) return@post
                val expandable = descLineCount(safeDesc) > DESC_COLLAPSED_TEXT_LINES
                applyDescFocusable(expandable = expandable)
                applyDescExpanded(expanded = expanded && expandable, expandable = expandable)
            }
        }

        private fun applyDescFocusable(expandable: Boolean) {
            binding.layoutDesc.isFocusable = expandable
            binding.layoutDesc.isClickable = expandable
        }

        private fun applyDescExpanded(expanded: Boolean, expandable: Boolean = true) {
            if (expanded) {
                binding.tvDesc.maxLines = Int.MAX_VALUE
                binding.tvDescMoreIndicator.isVisible = false
            } else {
                binding.tvDesc.maxLines = DESC_COLLAPSED_TEXT_LINES
                binding.tvDescMoreIndicator.isVisible = expandable
            }
        }

        private fun descLineCount(text: CharSequence): Int {
            val textWidth =
                (binding.tvDesc.width - binding.tvDesc.compoundPaddingLeft - binding.tvDesc.compoundPaddingRight)
                    .takeIf { it > 0 }
                    ?: (binding.layoutDesc.width - binding.layoutDesc.paddingLeft - binding.layoutDesc.paddingRight)
            if (textWidth <= 0) return 0
            @Suppress("DEPRECATION")
            return StaticLayout(
                text,
                binding.tvDesc.paint,
                textWidth,
                Layout.Alignment.ALIGN_NORMAL,
                binding.tvDesc.lineSpacingMultiplier,
                binding.tvDesc.lineSpacingExtra,
                binding.tvDesc.includeFontPadding,
            ).lineCount
        }

        private fun bindTags(tags: List<VideoTag>) {
            val safeTags = tags.filter { it.tagId > 0L && it.tagName.isNotBlank() }
            binding.chipGroupTags.isVisible = safeTags.isNotEmpty()
            if (safeTags.isEmpty()) {
                binding.chipGroupTags.removeAllViews()
                lastTagsKey = null
                return
            }

            val key = safeTags.joinToString("|") { it.stableKey() }
            if (key == lastTagsKey && binding.chipGroupTags.childCount == safeTags.size) return
            lastTagsKey = key

            binding.chipGroupTags.removeAllViews()
            val inflater = LayoutInflater.from(binding.root.context)
            for (tag in safeTags) {
                val chip =
                    inflater.inflate(blbl.cat3399.R.layout.item_video_detail_tag, binding.chipGroupTags, false) as Chip
                chip.text = tag.tagName
                chip.isCheckable = false
                chip.setOnClickListener { onTagClick(tag) }
                binding.chipGroupTags.addView(chip)
            }
        }

        private fun applyCoverContainerRatio(usePosterCover: Boolean) {
            val params = binding.clCoverContainer.layoutParams as? ConstraintLayout.LayoutParams ?: return
            val targetRatio = if (usePosterCover) "9:16" else "16:9"
            val targetWidthPx =
                binding.root.resources.getDimensionPixelSize(
                    if (usePosterCover) {
                        blbl.cat3399.R.dimen.video_detail_header_cover_width_poster
                    } else {
                        blbl.cat3399.R.dimen.video_detail_header_cover_width
                    },
                )
            if (params.dimensionRatio == targetRatio && params.width == targetWidthPx) return
            params.dimensionRatio = targetRatio
            params.width = targetWidthPx
            binding.clCoverContainer.layoutParams = params
        }

        private fun maybeAutoScrollParts(partsCards: List<VideoCard>, selectedKey: String?) {
            val safeSelected = selectedKey?.trim()?.takeIf { it.isNotBlank() } ?: return
            val idx = partsCards.indexOfFirst { cardStableKey(it) == safeSelected }.takeIf { it >= 0 } ?: return

            val firstKey = partsCards.firstOrNull()?.let(::cardStableKey).orEmpty()
            val lastKey = partsCards.lastOrNull()?.let(::cardStableKey).orEmpty()
            val autoScrollKey = "$safeSelected|$idx|${partsCards.size}|$firstKey|$lastKey"
            if (autoScrollKey == lastPartsAutoScrollKey) return
            lastPartsAutoScrollKey = autoScrollKey

            binding.recyclerParts.post {
                val lm = binding.recyclerParts.layoutManager as? LinearLayoutManager ?: return@post
                lm.scrollToPositionWithOffset(idx, binding.recyclerParts.paddingLeft)
            }
        }

        private fun maybeAutoScrollSeason(seasonCards: List<VideoCard>, selectedKey: String?) {
            val safeSelected = selectedKey?.trim()?.takeIf { it.isNotBlank() } ?: return
            val idx = seasonCards.indexOfFirst { cardStableKey(it) == safeSelected }.takeIf { it >= 0 } ?: return

            val firstKey = seasonCards.firstOrNull()?.let(::cardStableKey).orEmpty()
            val lastKey = seasonCards.lastOrNull()?.let(::cardStableKey).orEmpty()
            val autoScrollKey = "$safeSelected|$idx|${seasonCards.size}|$firstKey|$lastKey"
            if (autoScrollKey == lastSeasonAutoScrollKey) return
            lastSeasonAutoScrollKey = autoScrollKey

            binding.recyclerSeason.post {
                val lm = binding.recyclerSeason.layoutManager as? LinearLayoutManager ?: return@post
                lm.scrollToPositionWithOffset(idx, binding.recyclerSeason.paddingLeft)
            }
        }

        private fun scrollPartsToStart() {
            binding.recyclerParts.post {
                val lm = binding.recyclerParts.layoutManager as? LinearLayoutManager ?: return@post
                lm.scrollToPositionWithOffset(0, binding.recyclerParts.paddingLeft)
            }
        }

        private fun scrollSeasonToStart() {
            binding.recyclerSeason.post {
                val lm = binding.recyclerSeason.layoutManager as? LinearLayoutManager ?: return@post
                lm.scrollToPositionWithOffset(0, binding.recyclerSeason.paddingLeft)
            }
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
    }
}
