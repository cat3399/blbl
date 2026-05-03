package blbl.cat3399.feature.video

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.R
import blbl.cat3399.core.image.ImageLoader
import blbl.cat3399.core.image.ImageUrl
import blbl.cat3399.core.model.VideoCard
import blbl.cat3399.core.ui.DpadItemKeyHandler
import blbl.cat3399.core.ui.cloneInUserScale
import blbl.cat3399.core.ui.setDpadItemKeyHandler
import blbl.cat3399.core.util.Format
import blbl.cat3399.databinding.ItemVideoCardBinding
import kotlin.math.roundToInt

class VideoCardAdapter(
    private val onClick: (VideoCard, Int) -> Unit,
    private val onLongClick: ((VideoCard, Int) -> Boolean)? = null,
    private val actionDelegate: VideoCardActionDelegate? = null,
    private val fixedItemWidthDimenRes: Int? = null,
    private val fixedItemMarginDimenRes: Int? = null,
    private val stableIdKey: ((VideoCard) -> String)? = null,
    private val isSelected: ((VideoCard, Int) -> Boolean)? = null,
) : RecyclerView.Adapter<VideoCardAdapter.Vh>() {
    private val items = ArrayList<VideoCard>()
    private var expandedCardStableKey: String? = null
    private var selectedActionIndex: Int = 0

    private data class WatchProgressUi(
        val labelLeft: String,
        val labelRight: String?,
        val progressPermille: Int,
    )

    data class ActionOverlayUi(
        val actions: List<VideoCardQuickAction>,
        val selectedIndex: Int,
    )

    init {
        setHasStableIds(true)
    }

    fun invalidateSizing() {
        if (itemCount <= 0) return
        notifyItemRangeChanged(0, itemCount)
    }

    fun submit(list: List<VideoCard>) {
        items.clear()
        items.addAll(list)
        if (expandedCardStableKey != null && items.none { stableKeyFor(it) == expandedCardStableKey }) {
            expandedCardStableKey = null
            selectedActionIndex = 0
        }
        notifyDataSetChanged()
    }

    fun append(list: List<VideoCard>) {
        if (list.isEmpty()) return
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    fun snapshot(): List<VideoCard> = items.toList()

    fun removeByStableKey(stableKey: String): Int {
        val index = items.indexOfFirst { stableKeyFor(it) == stableKey }
        if (index < 0) return -1
        val removedKey = stableKeyFor(items[index])
        items.removeAt(index)
        if (expandedCardStableKey == removedKey) {
            expandedCardStableKey = null
            selectedActionIndex = 0
        }
        notifyItemRemoved(index)
        if (index < items.size) {
            notifyItemRangeChanged(index, items.size - index)
        }
        return index
    }

    override fun getItemId(position: Int): Long {
        val item = items[position]
        val key = stableKeyFor(item)
        return key.hashCode().toLong()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
        val binding =
            ItemVideoCardBinding.inflate(
                LayoutInflater.from(parent.context).cloneInUserScale(parent.context),
                parent,
                false,
            )
        return Vh(
            binding = binding,
            fixedItemWidthDimenRes = fixedItemWidthDimenRes,
            fixedItemMarginDimenRes = fixedItemMarginDimenRes,
            onItemClick = ::handleItemClick,
            onItemLongClick = ::handleItemLongClick,
            onOverlayActionClick = ::handleOverlayActionClick,
            onItemFocusLost = ::handleItemFocusLost,
            onOverlayActionSelect = ::updateSelectedActionIndex,
            isOverlayExpanded = ::isOverlayExpanded,
        )
    }

    override fun onBindViewHolder(holder: Vh, position: Int) {
        val item = items[position]
        holder.bind(
            item = item,
            position = position,
            isSelected = isSelected,
            overlayUi = currentOverlayUi(item, position),
            actionDelegate = actionDelegate,
        )
    }

    override fun getItemCount(): Int = items.size

    private fun stableKeyFor(item: VideoCard): String = stableIdKey?.invoke(item) ?: item.stableKey()

    private fun isOverlayExpanded(item: VideoCard): Boolean = expandedCardStableKey == stableKeyFor(item)

    private fun handleItemClick(
        item: VideoCard,
        position: Int,
        isRootFocused: Boolean,
    ) {
        val overlayUi = currentOverlayUi(item = item, position = position)
        if (overlayUi == null) {
            onClick(item, position)
            return
        }

        if (isRootFocused) {
            val action = overlayUi.actions.getOrNull(overlayUi.selectedIndex) ?: return
            collapseExpandedOverlay()
            actionDelegate?.onActionSelected(item, position, action)
        } else {
            collapseExpandedOverlay()
        }
    }

    private fun handleItemLongClick(
        item: VideoCard,
        position: Int,
        isPointerLongClick: Boolean,
    ): Boolean {
        val delegate = actionDelegate ?: return onLongClick?.invoke(item, position) ?: false
        return when (delegate.resolveLongPressAction(item, position)) {
            VideoCardConfiguredLongPressAction.MANUAL -> {
                openOverlay(
                    item = item,
                    initialSelectedIndex = if (isPointerLongClick) NO_ACTION_SELECTED else 0,
                )
            }

            VideoCardConfiguredLongPressAction.WATCH_LATER -> {
                delegate.manualActions(item, position)
                    .firstOrNull { it.id == VideoCardQuickActionId.WATCH_LATER }
                    ?.let { delegate.onActionSelected(item, position, it) }
                true
            }

            VideoCardConfiguredLongPressAction.OPEN_DETAIL -> {
                delegate.manualActions(item, position)
                    .firstOrNull { it.id == VideoCardQuickActionId.OPEN_DETAIL }
                    ?.let { delegate.onActionSelected(item, position, it) }
                true
            }

            VideoCardConfiguredLongPressAction.OPEN_UP -> {
                delegate.manualActions(item, position)
                    .firstOrNull { it.id == VideoCardQuickActionId.OPEN_UP }
                    ?.let { delegate.onActionSelected(item, position, it) }
                true
            }

            VideoCardConfiguredLongPressAction.DISMISS -> {
                delegate.manualActions(item, position)
                    .firstOrNull { it.id == VideoCardQuickActionId.DISMISS }
                    ?.let { delegate.onActionSelected(item, position, it) }
                true
            }
        }
    }

    private fun handleOverlayActionClick(
        item: VideoCard,
        position: Int,
        actionIndex: Int,
    ) {
        val overlayUi = currentOverlayUi(item = item, position = position) ?: return
        val action = overlayUi.actions.getOrNull(actionIndex) ?: return
        updateSelectedActionIndex(actionIndex)
        collapseExpandedOverlay()
        actionDelegate?.onActionSelected(item, position, action)
    }

    private fun handleItemFocusLost(item: VideoCard) {
        if (!isOverlayExpanded(item)) return
        collapseExpandedOverlay()
    }

    private fun updateSelectedActionIndex(index: Int) {
        val expandedKey = expandedCardStableKey ?: return
        val targetIndex = index.coerceIn(0, ACTION_BUTTON_COUNT - 1)
        if (selectedActionIndex == targetIndex) return
        selectedActionIndex = targetIndex
        notifyExpandedKeyChanged(expandedKey)
    }

    private fun openOverlay(
        item: VideoCard,
        initialSelectedIndex: Int,
    ): Boolean {
        val key = stableKeyFor(item)
        if (expandedCardStableKey == key) {
            if (selectedActionIndex == NO_ACTION_SELECTED && initialSelectedIndex != NO_ACTION_SELECTED) {
                selectedActionIndex = initialSelectedIndex
            }
            notifyExpandedKeyChanged(key)
            return true
        }

        val previousKey = expandedCardStableKey
        expandedCardStableKey = key
        selectedActionIndex = initialSelectedIndex
        previousKey?.let(::notifyExpandedKeyChanged)
        notifyExpandedKeyChanged(key)
        return true
    }

    private fun collapseExpandedOverlay() {
        val key = expandedCardStableKey ?: return
        expandedCardStableKey = null
        selectedActionIndex = 0
        notifyExpandedKeyChanged(key)
    }

    private fun notifyExpandedKeyChanged(stableKey: String) {
        val index = items.indexOfFirst { stableKeyFor(it) == stableKey }
        if (index >= 0) notifyItemChanged(index)
    }

    private fun currentOverlayUi(
        item: VideoCard,
        position: Int,
    ): ActionOverlayUi? {
        if (!isOverlayExpanded(item)) return null
        val actions = actionDelegate?.manualActions(item, position).orEmpty().take(ACTION_BUTTON_COUNT)
        if (actions.size != ACTION_BUTTON_COUNT) return null
        return ActionOverlayUi(
            actions = actions,
            selectedIndex = selectedActionIndex.takeIf { it in actions.indices } ?: NO_ACTION_SELECTED,
        )
    }

    companion object {
        private const val NO_ACTION_SELECTED = -1
        private const val ACTION_BUTTON_COUNT = 4
    }

    class Vh(
        private val binding: ItemVideoCardBinding,
        private val fixedItemWidthDimenRes: Int?,
        private val fixedItemMarginDimenRes: Int?,
        private val onItemClick: (VideoCard, Int, Boolean) -> Unit,
        private val onItemLongClick: (VideoCard, Int, Boolean) -> Boolean,
        private val onOverlayActionClick: (VideoCard, Int, Int) -> Unit,
        private val onItemFocusLost: (VideoCard) -> Unit,
        private val onOverlayActionSelect: (Int) -> Unit,
        private val isOverlayExpanded: (VideoCard) -> Boolean,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var pointerDownForLongClick: Boolean = false

        init {
            applyFixedSizing()
        }

        fun bind(
            item: VideoCard,
            position: Int,
            isSelected: ((VideoCard, Int) -> Boolean)?,
            overlayUi: ActionOverlayUi?,
            actionDelegate: VideoCardActionDelegate?,
        ) {
            applyFixedSizing()

            binding.root.isSelected = isSelected?.invoke(item, position) == true

            val watchProgressUi = buildWatchProgressUi(item)
            val coverLeftBottomText = item.coverLeftBottomText?.trim()
            val isEpisodeStyleCard = item.coverLeftBottomText != null
            binding.tvCoverLeftBottom.isVisible = watchProgressUi == null && coverLeftBottomText?.isNotBlank() == true
            binding.tvCoverLeftBottom.text = coverLeftBottomText.orEmpty()
            binding.tvProgressLeft.isVisible = watchProgressUi != null
            binding.tvProgressLeft.text = watchProgressUi?.labelLeft.orEmpty()
            binding.tvProgressTime.isVisible = watchProgressUi?.labelRight != null
            binding.tvProgressTime.text = watchProgressUi?.labelRight.orEmpty()
            binding.progressWatch.isVisible = watchProgressUi != null
            binding.progressWatch.progress = watchProgressUi?.progressPermille ?: 0

            binding.tvTitle.text = item.title
            val subtitleText =
                item.pubDateText
                    ?: if (item.ownerName.isBlank()) "" else "UP ${item.ownerName}"
            binding.tvSubtitle.text = subtitleText
            val pubDateText = item.pubDate?.let { Format.pubDateText(it) }.orEmpty()
            binding.tvPubdate.text = pubDateText
            val showSubtitleRow = !isEpisodeStyleCard && (subtitleText.isNotBlank() || pubDateText.isNotBlank())
            binding.llSubtitle.isVisible = showSubtitleRow
            binding.tvSubtitle.isVisible = showSubtitleRow && subtitleText.isNotBlank()
            binding.tvPubdate.isVisible = showSubtitleRow && pubDateText.isNotBlank()

            val showDuration = !isEpisodeStyleCard && watchProgressUi == null && item.durationSec > 0
            binding.tvDuration.isVisible = showDuration
            if (showDuration) {
                binding.tvDuration.text = Format.duration(item.durationSec)
            }

            val viewCount = item.view?.takeIf { it > 0 }
            val danmakuCount = item.danmaku?.takeIf { it > 0 }
            val showStats = !isEpisodeStyleCard && watchProgressUi == null && (viewCount != null || danmakuCount != null)
            binding.llStats.isVisible = showStats
            binding.ivStatPlay.isVisible = viewCount != null
            binding.tvView.isVisible = viewCount != null
            binding.ivStatDanmaku.isVisible = danmakuCount != null
            binding.tvDanmaku.isVisible = danmakuCount != null
            viewCount?.let { binding.tvView.text = Format.count(it) }
            danmakuCount?.let { binding.tvDanmaku.text = Format.count(it) }

            val accessBadgeText = item.accessBadgeText?.trim()?.takeIf { it.isNotBlank() }
            binding.tvChargeBadge.isVisible = accessBadgeText != null || item.isChargingArc
            binding.tvChargeBadge.text =
                accessBadgeText
                    ?: if (item.isChargingArc) {
                        binding.root.context.getString(R.string.badge_charging)
                    } else {
                        ""
                    }
            ImageLoader.loadInto(binding.ivCover, ImageUrl.cover(item.coverUrl))

            applyOverlayTranslations()
            bindActionOverlay(
                item = item,
                overlayUi = overlayUi,
                actionDelegate = actionDelegate,
            )

            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: return@setOnClickListener
                onItemClick(item, pos, binding.root.isFocused)
            }

            binding.root.setOnLongClickListener {
                val pos = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: return@setOnLongClickListener false
                onItemLongClick(item, pos, pointerDownForLongClick)
            }

            binding.root.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> pointerDownForLongClick = true
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL,
                    -> pointerDownForLongClick = false
                }
                false
            }

            binding.root.onFocusChangeListener =
                View.OnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) {
                        binding.root.post {
                            if (bindingAdapterPosition != RecyclerView.NO_POSITION && !binding.root.hasFocus()) {
                                onItemFocusLost(item)
                            }
                        }
                    }
                }
        }

        private fun applyFixedSizing() {
            if (fixedItemWidthDimenRes != null) {
                val w =
                    binding.root.resources
                        .getDimensionPixelSize(fixedItemWidthDimenRes)
                        .coerceAtLeast(1)
                val lp = binding.root.layoutParams
                if (lp != null && lp.width != w) {
                    lp.width = w
                    binding.root.layoutParams = lp
                }
            }

            if (fixedItemMarginDimenRes != null) {
                val margin = binding.root.resources.getDimensionPixelSize(fixedItemMarginDimenRes).coerceAtLeast(0)
                (binding.root.layoutParams as? MarginLayoutParams)?.let { lp ->
                    if (lp.leftMargin != margin || lp.topMargin != margin || lp.rightMargin != margin || lp.bottomMargin != margin) {
                        lp.setMargins(margin, margin, margin, margin)
                        binding.root.layoutParams = lp
                    }
                }
            }
        }

        private fun applyOverlayTranslations() {
            val res = binding.root.resources
            val textMargin = res.getDimensionPixelSize(R.dimen.video_card_text_margin)
            val padH = res.getDimensionPixelSize(R.dimen.video_card_duration_padding_h)
            val padV = res.getDimensionPixelSize(R.dimen.video_card_duration_padding_v)

            // "Closer to the left edge by 50%": the visible inset is (margin + inner padding),
            // so shift left by 50% of that total inset (more noticeable than margin-only).
            val insetX = textMargin + padH
            val shiftX = -insetX * 0.5f
            binding.llStats.translationX = shiftX
            binding.tvProgressLeft.translationX = shiftX
            binding.tvDuration.translationX = shiftX
            binding.tvProgressTime.translationX = shiftX

            // "Move down 20%": apply 20% of overlay height (and a tiny baseline from margin)
            // so the change is visible across different UI scales.
            fun applyShiftY() {
                val overlayH =
                    maxOf(
                        maxOf(binding.llStats.height, binding.tvDuration.height),
                        maxOf(binding.tvProgressLeft.height, binding.tvProgressTime.height),
                    )
                val shiftY = (textMargin + padV) * 0.2f + overlayH * 0.2f
                binding.llStats.translationY = shiftY
                binding.tvProgressLeft.translationY = shiftY
                binding.tvDuration.translationY = shiftY
                binding.tvProgressTime.translationY = shiftY
            }

            if (binding.llStats.height > 0 ||
                binding.tvDuration.height > 0 ||
                binding.tvProgressLeft.height > 0 ||
                binding.tvProgressTime.height > 0
            ) {
                applyShiftY()
            } else {
                binding.root.post { applyShiftY() }
            }
        }

        private fun bindActionOverlay(
            item: VideoCard,
            overlayUi: ActionOverlayUi?,
            actionDelegate: VideoCardActionDelegate?,
        ) {
            val isExpanded = overlayUi != null
            binding.vActionScrim.isVisible = isExpanded
            binding.llActions.isVisible = isExpanded
            if (isExpanded) {
                binding.llStats.isVisible = false
                binding.tvDuration.isVisible = false
                binding.tvProgressLeft.isVisible = false
                binding.tvProgressTime.isVisible = false
                binding.progressWatch.isVisible = false
                binding.tvCoverLeftBottom.isVisible = false
            }

            val actionButtons =
                listOf(
                    binding.btnActionWatchLater,
                    binding.btnActionDetail,
                    binding.btnActionUp,
                    binding.btnActionDismiss,
                )
            val actionIcons =
                listOf(
                    binding.ivActionWatchLater,
                    binding.ivActionDetail,
                    binding.ivActionUp,
                    binding.ivActionDismiss,
                )

            if (overlayUi == null) {
                actionButtons.forEach { it.isSelected = false }
                binding.ivActionDismiss.contentDescription = binding.root.context.getString(R.string.video_card_action_not_interested)
                binding.root.setDpadItemKeyHandler(null)
                return
            }

            overlayUi.actions.forEachIndexed { index, action ->
                actionButtons[index].isSelected = overlayUi.selectedIndex == index
                actionIcons[index].setImageResource(action.iconResId)
                actionIcons[index].contentDescription = action.contentDescription
                actionButtons[index].setOnClickListener {
                    val pos = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: return@setOnClickListener
                    onOverlayActionClick(item, pos, index)
                }
            }
            actionButtons.forEachIndexed { index, button ->
                button.isSelected = overlayUi.selectedIndex == index
            }

            binding.root.setDpadItemKeyHandler(
                if (actionDelegate == null) {
                    null
                } else {
                    DpadItemKeyHandler { _, keyCode, event ->
                        if (!isOverlayExpanded(item)) return@DpadItemKeyHandler false
                        when (keyCode) {
                            KeyEvent.KEYCODE_BACK,
                            KeyEvent.KEYCODE_ESCAPE,
                            KeyEvent.KEYCODE_BUTTON_B,
                            ->
                                if (event.action == KeyEvent.ACTION_DOWN) {
                                    onItemFocusLost(item)
                                    true
                                } else {
                                    false
                                }

                            KeyEvent.KEYCODE_DPAD_LEFT ->
                                if (event.action == KeyEvent.ACTION_DOWN) {
                                    if (overlayUi.selectedIndex > 0) {
                                        onOverlayActionSelect(overlayUi.selectedIndex - 1)
                                        true
                                    } else {
                                        onItemFocusLost(item)
                                        false
                                    }
                                } else {
                                    false
                                }

                            KeyEvent.KEYCODE_DPAD_RIGHT ->
                                if (event.action == KeyEvent.ACTION_DOWN) {
                                    if (overlayUi.selectedIndex < overlayUi.actions.lastIndex) {
                                        onOverlayActionSelect(overlayUi.selectedIndex + 1)
                                        true
                                    } else {
                                        onItemFocusLost(item)
                                        false
                                    }
                                } else {
                                    false
                                }

                            KeyEvent.KEYCODE_DPAD_UP,
                            KeyEvent.KEYCODE_DPAD_DOWN,
                            ->
                                if (event.action == KeyEvent.ACTION_DOWN) {
                                    onItemFocusLost(item)
                                    false
                                } else {
                                    false
                                }

                            KeyEvent.KEYCODE_DPAD_CENTER,
                            KeyEvent.KEYCODE_ENTER,
                            KeyEvent.KEYCODE_NUMPAD_ENTER,
                            ->
                                event.repeatCount > 0

                            else -> false
                        }
                    }
                },
            )
        }

        private fun buildWatchProgressUi(item: VideoCard): WatchProgressUi? {
            val durationSec = item.durationSec.takeIf { it > 0 } ?: return if (item.progressFinished) {
                WatchProgressUi(
                    labelLeft = binding.root.context.getString(R.string.video_card_progress_complete),
                    labelRight = null,
                    progressPermille = 1000,
                )
            } else {
                null
            }
            if (item.progressFinished) {
                val full = Format.clock(durationSec.toLong())
                return WatchProgressUi(
                    labelLeft = binding.root.context.getString(R.string.video_card_progress_complete),
                    labelRight = "$full / $full",
                    progressPermille = 1000,
                )
            }

            val positionSec = item.progressSec?.takeIf { it > 0L } ?: return null
            val clampedPositionSec = positionSec.coerceIn(0L, durationSec.toLong())
            if (clampedPositionSec >= durationSec.toLong()) {
                val full = Format.clock(durationSec.toLong())
                return WatchProgressUi(
                    labelLeft = binding.root.context.getString(R.string.video_card_progress_complete),
                    labelRight = "$full / $full",
                    progressPermille = 1000,
                )
            }

            val percent =
                ((clampedPositionSec.toDouble() * 100.0) / durationSec.toDouble())
                    .roundToInt()
                    .coerceIn(1, 99)
            val permille =
                ((clampedPositionSec.toDouble() * 1000.0) / durationSec.toDouble())
                    .roundToInt()
                    .coerceIn(1, 999)
            return WatchProgressUi(
                labelLeft = "${percent}%",
                labelRight = "${Format.clock(clampedPositionSec)} / ${Format.clock(durationSec.toLong())}",
                progressPermille = permille,
            )
        }
    }
}
