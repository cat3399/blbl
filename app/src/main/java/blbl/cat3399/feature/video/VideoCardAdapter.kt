package blbl.cat3399.feature.video

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.R
import blbl.cat3399.core.image.ImageLoader
import blbl.cat3399.core.image.ImageUrl
import blbl.cat3399.core.model.VideoCard
import blbl.cat3399.core.util.Format
import blbl.cat3399.databinding.ItemVideoCardBinding

class VideoCardAdapter(
    private val onClick: (VideoCard) -> Unit,
) : RecyclerView.Adapter<VideoCardAdapter.Vh>() {
    private val items = ArrayList<VideoCard>()
    private var tvMode: Boolean = false

    init {
        setHasStableIds(true)
    }

    fun setTvMode(enabled: Boolean) {
        if (tvMode == enabled) return
        tvMode = enabled
        notifyItemRangeChanged(0, itemCount)
    }

    fun submit(list: List<VideoCard>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun append(list: List<VideoCard>) {
        if (list.isEmpty()) return
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    override fun getItemId(position: Int): Long = items[position].bvid.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
        val binding = ItemVideoCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Vh(binding)
    }

    override fun onBindViewHolder(holder: Vh, position: Int) = holder.bind(items[position], tvMode, onClick)

    override fun getItemCount(): Int = items.size

    class Vh(private val binding: ItemVideoCardBinding) : RecyclerView.ViewHolder(binding.root) {
        private var lastTvMode: Boolean? = null

        fun bind(item: VideoCard, tvMode: Boolean, onClick: (VideoCard) -> Unit) {
            if (lastTvMode != tvMode) {
                applySizing(tvMode)
                lastTvMode = tvMode
            }

            binding.tvTitle.text = item.title
            binding.tvSubtitle.text =
                item.pubDateText
                    ?: if (item.ownerName.isBlank()) "" else "UP ${item.ownerName}"
            val pubDateText = item.pubDate?.let { Format.pubDateText(it) }.orEmpty()
            binding.tvPubdate.text = pubDateText
            binding.tvPubdate.isVisible = pubDateText.isNotBlank()
            binding.tvDuration.text = Format.duration(item.durationSec)
            binding.tvView.text = Format.count(item.view)
            binding.tvDanmaku.text = Format.count(item.danmaku)
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
            (binding.tvDuration.layoutParams as? MarginLayoutParams)?.let { lp ->
                if (lp.leftMargin != textMargin || lp.topMargin != textMargin || lp.rightMargin != textMargin || lp.bottomMargin != textMargin) {
                    lp.setMargins(textMargin, textMargin, textMargin, textMargin)
                    binding.tvDuration.layoutParams = lp
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
            (binding.llSubtitle.layoutParams as? MarginLayoutParams)?.let { lp ->
                if (lp.leftMargin != textMargin || lp.rightMargin != textMargin || lp.bottomMargin != textMargin) {
                    lp.leftMargin = textMargin
                    lp.rightMargin = textMargin
                    lp.bottomMargin = textMargin
                    binding.llSubtitle.layoutParams = lp
                }
            }

            val padH = px(if (tvMode) R.dimen.video_card_duration_padding_h_tv else R.dimen.video_card_duration_padding_h)
            val padV = px(if (tvMode) R.dimen.video_card_duration_padding_v_tv else R.dimen.video_card_duration_padding_v)
            binding.tvDuration.setPadding(padH, padV, padH, padV)
            binding.llStats.setPadding(padH, padV, padH, padV)

            binding.tvDuration.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                pxF(if (tvMode) R.dimen.video_card_duration_text_size_tv else R.dimen.video_card_duration_text_size),
            )
            binding.tvView.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                pxF(if (tvMode) R.dimen.video_card_stat_text_size_tv else R.dimen.video_card_stat_text_size),
            )
            binding.tvDanmaku.setTextSize(
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
            binding.tvPubdate.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                pxF(if (tvMode) R.dimen.video_card_subtitle_text_size_tv else R.dimen.video_card_subtitle_text_size),
            )

            val iconSize = px(if (tvMode) R.dimen.video_card_stat_icon_size_tv else R.dimen.video_card_stat_icon_size)
            val playLp = binding.ivStatPlay.layoutParams
            if (playLp.width != iconSize || playLp.height != iconSize) {
                playLp.width = iconSize
                playLp.height = iconSize
                binding.ivStatPlay.layoutParams = playLp
            }
            val danLp = binding.ivStatDanmaku.layoutParams
            if (danLp.width != iconSize || danLp.height != iconSize) {
                danLp.width = iconSize
                danLp.height = iconSize
                binding.ivStatDanmaku.layoutParams = danLp
            }
        }
    }
}
