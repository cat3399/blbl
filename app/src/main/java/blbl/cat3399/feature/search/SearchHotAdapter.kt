package blbl.cat3399.feature.search

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.R
import blbl.cat3399.databinding.ItemSearchHotBinding

class SearchHotAdapter(
    private val onClick: (String) -> Unit,
) : RecyclerView.Adapter<SearchHotAdapter.Vh>() {
    private val items = ArrayList<String>()
    private var tvMode: Boolean = false

    init {
        setHasStableIds(true)
    }

    fun setTvMode(enabled: Boolean) {
        if (tvMode == enabled) return
        tvMode = enabled
        notifyItemRangeChanged(0, itemCount)
    }

    fun submit(list: List<String>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemId(position: Int): Long = items[position].hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
        val binding = ItemSearchHotBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Vh(binding)
    }

    override fun onBindViewHolder(holder: Vh, position: Int) {
        holder.bind(items[position], tvMode, onClick)
    }

    override fun getItemCount(): Int = items.size

    class Vh(private val binding: ItemSearchHotBinding) : RecyclerView.ViewHolder(binding.root) {
        private var lastTvMode: Boolean? = null

        fun bind(keyword: String, tvMode: Boolean, onClick: (String) -> Unit) {
            if (lastTvMode != tvMode) {
                applySizing(tvMode)
                lastTvMode = tvMode
            }
            binding.tvKeyword.text = keyword
            binding.root.setOnClickListener { onClick(keyword) }
        }

        private fun applySizing(tvMode: Boolean) {
            fun px(id: Int): Int = binding.root.resources.getDimensionPixelSize(id)
            fun pxF(id: Int): Float = binding.root.resources.getDimension(id)

            (binding.card.layoutParams as? MarginLayoutParams)?.let { lp ->
                val mb = px(if (tvMode) R.dimen.search_hot_margin_bottom_tv else R.dimen.search_hot_margin_bottom)
                if (lp.bottomMargin != mb) {
                    lp.bottomMargin = mb
                    binding.card.layoutParams = lp
                }
            }

            val padH = px(if (tvMode) R.dimen.search_hot_padding_h_tv else R.dimen.search_hot_padding_h)
            val padV = px(if (tvMode) R.dimen.search_hot_padding_v_tv else R.dimen.search_hot_padding_v)
            if (binding.container.paddingLeft != padH || binding.container.paddingTop != padV || binding.container.paddingRight != padH || binding.container.paddingBottom != padV) {
                binding.container.setPadding(padH, padV, padH, padV)
            }

            val iconSize = px(if (tvMode) R.dimen.search_hot_icon_size_tv else R.dimen.search_hot_icon_size)
            val iconLp = binding.ivIcon.layoutParams as? MarginLayoutParams
            if (iconLp != null) {
                val me = px(if (tvMode) R.dimen.search_hot_icon_margin_end_tv else R.dimen.search_hot_icon_margin_end)
                if (iconLp.width != iconSize || iconLp.height != iconSize || iconLp.marginEnd != me) {
                    iconLp.width = iconSize
                    iconLp.height = iconSize
                    iconLp.marginEnd = me
                    binding.ivIcon.layoutParams = iconLp
                }
            }

            binding.tvKeyword.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                pxF(if (tvMode) R.dimen.search_hot_text_size_tv else R.dimen.search_hot_text_size),
            )
        }
    }
}
