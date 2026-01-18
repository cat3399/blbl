package blbl.cat3399.feature.my

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.core.image.ImageLoader
import blbl.cat3399.core.image.ImageUrl
import blbl.cat3399.core.model.BangumiSeason
import blbl.cat3399.core.ui.UiScale
import blbl.cat3399.core.util.pgcAccessBadgeTextOf
import blbl.cat3399.databinding.ItemBangumiFollowBinding
import kotlin.math.roundToInt

class BangumiFollowAdapter(
    private val onClick: (position: Int, season: BangumiSeason) -> Unit,
) : RecyclerView.Adapter<BangumiFollowAdapter.Vh>() {
    private val items = ArrayList<BangumiSeason>()
    private var tvMode: Boolean = false

    init {
        setHasStableIds(true)
    }

    fun setTvMode(enabled: Boolean) {
        tvMode = enabled
        notifyItemRangeChanged(0, itemCount)
    }

    fun submit(list: List<BangumiSeason>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun append(list: List<BangumiSeason>) {
        if (list.isEmpty()) return
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    override fun getItemId(position: Int): Long = items[position].seasonId

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
        val binding = ItemBangumiFollowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Vh(binding)
    }

    override fun onBindViewHolder(holder: Vh, position: Int) = holder.bind(items[position], tvMode, onClick)

    override fun getItemCount(): Int = items.size

    class Vh(private val binding: ItemBangumiFollowBinding) : RecyclerView.ViewHolder(binding.root) {
        private var lastTvMode: Boolean? = null
        private var lastUiScale: Float? = null
        private var baseRootMargins: IntArray? = null
        private var baseCornerRadiusPx: Float? = null
        private var baseStrokeWidthPx: Int? = null
        private var baseBadgeHeight: Int? = null
        private var baseBadgeMargins: IntArray? = null
        private var baseBadgeMinWidth: Int? = null
        private var baseBadgePadding: IntArray? = null
        private var baseBadgeTextSizePx: Float? = null
        private var baseTitleMargins: IntArray? = null
        private var baseTitleTextSizePx: Float? = null
        private var baseSubtitleMargins: IntArray? = null
        private var baseSubtitleTextSizePx: Float? = null

        fun bind(item: BangumiSeason, tvMode: Boolean, onClick: (position: Int, season: BangumiSeason) -> Unit) {
            val uiScale = UiScale.factor(binding.root.context, tvMode)
            if (lastTvMode != tvMode || lastUiScale != uiScale) {
                applySizing(uiScale)
                lastTvMode = tvMode
                lastUiScale = uiScale
            }

            binding.tvTitle.text = item.title

            val badgeText = pgcAccessBadgeTextOf(item.badgeEp, item.badge)
            binding.tvAccessBadgeText.isVisible = badgeText != null
            binding.tvAccessBadgeText.text = badgeText.orEmpty()

            val metaParts =
                buildList {
                    item.seasonTypeName?.let { add(it) }
                    val progress =
                        item.progressText?.takeIf { it.isNotBlank() }
                            ?: item.lastEpIndex?.let { "看到第${it}话" }
                    progress?.let { add(it) }
                    when {
                        item.isFinish == true -> add("已完结")
                        item.newestEpIndex != null -> add("更新至${item.newestEpIndex}话")
                        item.totalCount != null -> add("共${item.totalCount}话")
                    }
                }
            val subtitle = metaParts.joinToString(" · ")
            binding.tvSubtitle.text = subtitle
            binding.tvSubtitle.isVisible = subtitle.isNotBlank()
            ImageLoader.loadInto(binding.ivCover, ImageUrl.poster(item.coverUrl))

            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: return@setOnClickListener
                onClick(pos, item)
            }
        }

        private fun applySizing(uiScale: Float) {
            fun scaled(valuePx: Int): Int = (valuePx * uiScale).roundToInt().coerceAtLeast(0)
            fun scaledF(valuePx: Float): Float = (valuePx * uiScale).coerceAtLeast(0f)

            (binding.root.layoutParams as? MarginLayoutParams)?.let { lp ->
                val base =
                    baseRootMargins
                        ?: intArrayOf(lp.leftMargin, lp.topMargin, lp.rightMargin, lp.bottomMargin).also { baseRootMargins = it }
                val l = scaled(base[0])
                val t = scaled(base[1])
                val r = scaled(base[2])
                val b = scaled(base[3])
                if (lp.leftMargin != l || lp.topMargin != t || lp.rightMargin != r || lp.bottomMargin != b) {
                    lp.setMargins(l, t, r, b)
                    binding.root.layoutParams = lp
                }
            }

            val baseRadius = baseCornerRadiusPx ?: binding.root.radius.also { baseCornerRadiusPx = it }
            val radius = scaledF(baseRadius)
            if (binding.root.radius != radius) binding.root.radius = radius

            val baseStroke = baseStrokeWidthPx ?: binding.root.strokeWidth.also { baseStrokeWidthPx = it }
            val stroke = scaled(baseStroke)
            if (binding.root.strokeWidth != stroke) binding.root.strokeWidth = stroke

            val badgeLp = binding.tvAccessBadgeText.layoutParams as? MarginLayoutParams
            if (badgeLp != null) {
                val base =
                    baseBadgeMargins
                        ?: intArrayOf(badgeLp.leftMargin, badgeLp.topMargin, badgeLp.rightMargin, badgeLp.bottomMargin).also {
                            baseBadgeMargins = it
                        }
                val l = scaled(base[0])
                val t = scaled(base[1])
                val r = scaled(base[2])
                val b = scaled(base[3])
                if (badgeLp.leftMargin != l || badgeLp.topMargin != t || badgeLp.rightMargin != r || badgeLp.bottomMargin != b) {
                    badgeLp.setMargins(l, t, r, b)
                    binding.tvAccessBadgeText.layoutParams = badgeLp
                }
            }

            val baseBadgeH =
                baseBadgeHeight ?: binding.tvAccessBadgeText.layoutParams?.height?.takeIf { it > 0 }?.also { baseBadgeHeight = it }
            if (baseBadgeH != null) {
                val h = scaled(baseBadgeH).coerceAtLeast(1)
                val lp = binding.tvAccessBadgeText.layoutParams
                if (lp != null && lp.height != h) {
                    lp.height = h
                    binding.tvAccessBadgeText.layoutParams = lp
                }
            }

            val baseMinW = baseBadgeMinWidth ?: binding.tvAccessBadgeText.minWidth.also { baseBadgeMinWidth = it }
            val minW = scaled(baseMinW)
            if (binding.tvAccessBadgeText.minWidth != minW) binding.tvAccessBadgeText.minWidth = minW

            val basePad =
                baseBadgePadding
                    ?: intArrayOf(
                        binding.tvAccessBadgeText.paddingLeft,
                        binding.tvAccessBadgeText.paddingTop,
                        binding.tvAccessBadgeText.paddingRight,
                        binding.tvAccessBadgeText.paddingBottom,
                    ).also { baseBadgePadding = it }
            val pl = scaled(basePad[0])
            val pt = scaled(basePad[1])
            val pr = scaled(basePad[2])
            val pb = scaled(basePad[3])
            if (
                binding.tvAccessBadgeText.paddingLeft != pl ||
                binding.tvAccessBadgeText.paddingTop != pt ||
                binding.tvAccessBadgeText.paddingRight != pr ||
                binding.tvAccessBadgeText.paddingBottom != pb
            ) {
                binding.tvAccessBadgeText.setPadding(pl, pt, pr, pb)
            }

            val badgeTextSize = scaledF(baseBadgeTextSizePx ?: binding.tvAccessBadgeText.textSize.also { baseBadgeTextSizePx = it })
            binding.tvAccessBadgeText.setTextSize(TypedValue.COMPLEX_UNIT_PX, badgeTextSize.coerceAtLeast(1f))

            val titleLp = binding.tvTitle.layoutParams as? MarginLayoutParams
            if (titleLp != null) {
                val base =
                    baseTitleMargins
                        ?: intArrayOf(titleLp.leftMargin, titleLp.topMargin, titleLp.rightMargin, titleLp.bottomMargin).also {
                            baseTitleMargins = it
                        }
                val l = scaled(base[0])
                val t = scaled(base[1])
                val r = scaled(base[2])
                val b = scaled(base[3])
                if (titleLp.leftMargin != l || titleLp.topMargin != t || titleLp.rightMargin != r || titleLp.bottomMargin != b) {
                    titleLp.setMargins(l, t, r, b)
                    binding.tvTitle.layoutParams = titleLp
                }
            }

            val titleTextSize = scaledF(baseTitleTextSizePx ?: binding.tvTitle.textSize.also { baseTitleTextSizePx = it })
            binding.tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, titleTextSize.coerceAtLeast(1f))

            val subtitleLp = binding.tvSubtitle.layoutParams as? MarginLayoutParams
            if (subtitleLp != null) {
                val base =
                    baseSubtitleMargins
                        ?: intArrayOf(
                            subtitleLp.leftMargin,
                            subtitleLp.topMargin,
                            subtitleLp.rightMargin,
                            subtitleLp.bottomMargin,
                        ).also { baseSubtitleMargins = it }
                val l = scaled(base[0])
                val t = scaled(base[1])
                val r = scaled(base[2])
                val b = scaled(base[3])
                if (subtitleLp.leftMargin != l || subtitleLp.topMargin != t || subtitleLp.rightMargin != r || subtitleLp.bottomMargin != b) {
                    subtitleLp.setMargins(l, t, r, b)
                    binding.tvSubtitle.layoutParams = subtitleLp
                }
            }

            val subtitleTextSize = scaledF(baseSubtitleTextSizePx ?: binding.tvSubtitle.textSize.also { baseSubtitleTextSizePx = it })
            binding.tvSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, subtitleTextSize.coerceAtLeast(1f))
        }
    }
}
