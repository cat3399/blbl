package blbl.cat3399.feature.live

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.R
import blbl.cat3399.core.image.ImageLoader
import blbl.cat3399.core.image.ImageUrl
import blbl.cat3399.core.model.LiveRoomCard
import blbl.cat3399.core.util.Format
import blbl.cat3399.databinding.ItemLiveCardBinding

class LiveRoomAdapter(
    private val onClick: (LiveRoomCard) -> Unit,
) : RecyclerView.Adapter<LiveRoomAdapter.Vh>() {
    private val items = ArrayList<LiveRoomCard>()
    private var tvMode: Boolean = false

    init {
        setHasStableIds(true)
    }

    fun setTvMode(enabled: Boolean) {
        if (tvMode == enabled) return
        tvMode = enabled
        notifyItemRangeChanged(0, itemCount)
    }

    fun submit(list: List<LiveRoomCard>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun append(list: List<LiveRoomCard>) {
        if (list.isEmpty()) return
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    override fun getItemId(position: Int): Long = items[position].roomId

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
        val binding = ItemLiveCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Vh(binding)
    }

    override fun onBindViewHolder(holder: Vh, position: Int) = holder.bind(items[position], tvMode, onClick)

    override fun getItemCount(): Int = items.size

    class Vh(private val binding: ItemLiveCardBinding) : RecyclerView.ViewHolder(binding.root) {
        private var lastTvMode: Boolean? = null

        fun bind(item: LiveRoomCard, tvMode: Boolean, onClick: (LiveRoomCard) -> Unit) {
            if (lastTvMode != tvMode) {
                applySizing(tvMode)
                lastTvMode = tvMode
            }

            binding.tvTitle.text = item.title
            binding.tvSubtitle.text =
                buildString {
                    if (item.uname.isNotBlank()) append(item.uname)
                    val area = item.areaName ?: item.parentAreaName
                    if (!area.isNullOrBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append(area)
                    }
                    if (!item.isLive) {
                        if (isNotEmpty()) append(" · ")
                        append("未开播")
                    }
                }
            binding.tvOnline.text = if (item.isLive) Format.count(item.online) else "-"
            binding.tvBadge.visibility = if (item.isLive) View.VISIBLE else View.GONE
            ImageLoader.loadInto(binding.ivCover, ImageUrl.cover(item.coverUrl))
            binding.root.setOnClickListener { onClick(item) }
        }

        private fun applySizing(tvMode: Boolean) {
            fun px(id: Int): Int = binding.root.resources.getDimensionPixelSize(id)
            fun pxF(id: Int): Float = binding.root.resources.getDimension(id)

            val margin = px(if (tvMode) R.dimen.video_card_margin_tv else R.dimen.video_card_margin)
            val rootLp = binding.root.layoutParams as? MarginLayoutParams
            if (rootLp != null) {
                if (rootLp.leftMargin != margin || rootLp.topMargin != margin || rootLp.rightMargin != margin || rootLp.bottomMargin != margin) {
                    rootLp.setMargins(margin, margin, margin, margin)
                    binding.root.layoutParams = rootLp
                }
            }

            val textMargin = px(if (tvMode) R.dimen.video_card_text_margin_tv else R.dimen.video_card_text_margin)
            (binding.tvBadge.layoutParams as? MarginLayoutParams)?.let { lp ->
                if (lp.leftMargin != textMargin || lp.topMargin != textMargin || lp.rightMargin != textMargin || lp.bottomMargin != textMargin) {
                    lp.setMargins(textMargin, textMargin, textMargin, textMargin)
                    binding.tvBadge.layoutParams = lp
                }
            }
            (binding.llStats.layoutParams as? MarginLayoutParams)?.let { lp ->
                if (lp.leftMargin != textMargin || lp.topMargin != textMargin || lp.rightMargin != textMargin || lp.bottomMargin != textMargin) {
                    lp.setMargins(textMargin, textMargin, textMargin, textMargin)
                    binding.llStats.layoutParams = lp
                }
            }
            (binding.tvTitle.layoutParams as? MarginLayoutParams)?.let { lp ->
                if (lp.leftMargin != textMargin || lp.topMargin != textMargin || lp.rightMargin != textMargin || lp.bottomMargin != textMargin) {
                    lp.setMargins(textMargin, textMargin, textMargin, textMargin)
                    binding.tvTitle.layoutParams = lp
                }
            }
            (binding.tvSubtitle.layoutParams as? MarginLayoutParams)?.let { lp ->
                if (lp.leftMargin != textMargin || lp.rightMargin != textMargin || lp.bottomMargin != textMargin) {
                    lp.leftMargin = textMargin
                    lp.rightMargin = textMargin
                    lp.bottomMargin = textMargin
                    binding.tvSubtitle.layoutParams = lp
                }
            }

            val padH = px(if (tvMode) R.dimen.video_card_duration_padding_h_tv else R.dimen.video_card_duration_padding_h)
            val padV = px(if (tvMode) R.dimen.video_card_duration_padding_v_tv else R.dimen.video_card_duration_padding_v)
            binding.tvBadge.setPadding(padH, padV, padH, padV)
            binding.llStats.setPadding(padH, padV, padH, padV)

            binding.tvBadge.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                pxF(if (tvMode) R.dimen.video_card_duration_text_size_tv else R.dimen.video_card_duration_text_size),
            )
            binding.tvOnline.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                pxF(if (tvMode) R.dimen.video_card_stat_text_size_tv else R.dimen.video_card_stat_text_size),
            )
            binding.tvTitle.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                pxF(if (tvMode) R.dimen.video_card_title_text_size_tv else R.dimen.video_card_title_text_size),
            )
            binding.tvSubtitle.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                pxF(if (tvMode) R.dimen.video_card_subtitle_text_size_tv else R.dimen.video_card_subtitle_text_size),
            )

            val iconSize = px(if (tvMode) R.dimen.video_card_stat_icon_size_tv else R.dimen.video_card_stat_icon_size)
            val iconLp = binding.ivStatOnline.layoutParams
            if (iconLp.width != iconSize || iconLp.height != iconSize) {
                iconLp.width = iconSize
                iconLp.height = iconSize
                binding.ivStatOnline.layoutParams = iconLp
            }
        }
    }
}
