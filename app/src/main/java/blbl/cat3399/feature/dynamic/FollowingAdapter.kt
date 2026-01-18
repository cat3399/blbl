package blbl.cat3399.feature.dynamic

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.R
import blbl.cat3399.core.image.ImageLoader
import blbl.cat3399.core.image.ImageUrl
import blbl.cat3399.core.ui.UiScale
import blbl.cat3399.databinding.ItemFollowingBinding
import kotlin.math.roundToInt

class FollowingAdapter(
    private val onClick: (FollowingUi) -> Unit,
) : RecyclerView.Adapter<FollowingAdapter.Vh>() {
    data class FollowingUi(
        val mid: Long,
        val name: String,
        val avatarUrl: String?,
        val isAll: Boolean = false,
    )

    private val items = ArrayList<FollowingUi>()
    private var selectedMid: Long = MID_ALL
    private var tvMode: Boolean = false

    init {
        setHasStableIds(true)
    }

    fun setTvMode(enabled: Boolean) {
        tvMode = enabled
        notifyItemRangeChanged(0, itemCount)
    }

    fun submit(list: List<FollowingUi>, selected: Long = MID_ALL) {
        val prevSelected = selectedMid
        val sameItems =
            items.size == list.size &&
                items.indices.all { idx -> items[idx].mid == list[idx].mid }

        selectedMid = selected
        if (sameItems) {
            val prevPos = items.indexOfFirst { it.mid == prevSelected }
            val newPos = items.indexOfFirst { it.mid == selectedMid }
            if (prevPos >= 0) notifyItemChanged(prevPos)
            if (newPos >= 0 && newPos != prevPos) notifyItemChanged(newPos)
            return
        }

        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemId(position: Int): Long = items[position].mid

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
        val binding = ItemFollowingBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Vh(binding)
    }

    override fun onBindViewHolder(holder: Vh, position: Int) =
        holder.bind(items[position], tvMode, items[position].mid == selectedMid, onClick)

    override fun getItemCount(): Int = items.size

    class Vh(private val binding: ItemFollowingBinding) : RecyclerView.ViewHolder(binding.root) {
        private var lastTvMode: Boolean? = null
        private var lastUiScale: Float? = null

        fun bind(item: FollowingUi, tvMode: Boolean, selected: Boolean, onClick: (FollowingUi) -> Unit) {
            val uiScale = UiScale.factor(binding.root.context, tvMode)
            if (lastTvMode != tvMode || lastUiScale != uiScale) {
                applySizing(tvMode, uiScale)
                lastTvMode = tvMode
                lastUiScale = uiScale
            }

            binding.tvName.text = item.name
            if (item.isAll) {
                binding.ivAvatar.setImageResource(blbl.cat3399.R.drawable.ic_all)
                binding.ivAvatar.imageTintList =
                    android.content.res.ColorStateList.valueOf(
                        androidx.core.content.ContextCompat.getColor(binding.root.context, blbl.cat3399.R.color.blbl_text),
                    )
            } else {
                binding.ivAvatar.imageTintList = null
                ImageLoader.loadInto(binding.ivAvatar, ImageUrl.avatar(item.avatarUrl))
            }
            binding.vSelected.visibility = if (selected) android.view.View.VISIBLE else android.view.View.GONE
            binding.root.isSelected = selected
            binding.tvName.setTextColor(
                ContextCompat.getColor(
                    binding.root.context,
                    if (selected) blbl.cat3399.R.color.blbl_text else blbl.cat3399.R.color.blbl_text_secondary,
                ),
            )
            binding.root.setOnClickListener { onClick(item) }
        }

        private fun applySizing(tvMode: Boolean, uiScale: Float) {
            fun px(id: Int): Int = binding.root.resources.getDimensionPixelSize(id)
            fun pxF(id: Int): Float = binding.root.resources.getDimension(id)
            fun scaledPx(id: Int): Int = (px(id) * uiScale).roundToInt().coerceAtLeast(0)
            fun scaledPxF(id: Int): Float = pxF(id) * uiScale

            val height = scaledPx(if (tvMode) R.dimen.following_item_height_tv else R.dimen.following_item_height).coerceAtLeast(1)
            val rootLp = binding.root.layoutParams
            if (rootLp != null && rootLp.height != height) {
                rootLp.height = height
                binding.root.layoutParams = rootLp
            }

            (binding.root.layoutParams as? MarginLayoutParams)?.let { lp ->
                val mv = scaledPx(if (tvMode) R.dimen.following_item_margin_v_tv else R.dimen.following_item_margin_v)
                val mh = scaledPx(if (tvMode) R.dimen.following_item_margin_h_tv else R.dimen.following_item_margin_h)
                if (lp.topMargin != mv || lp.bottomMargin != mv || lp.leftMargin != mh || lp.rightMargin != mh) {
                    lp.topMargin = mv
                    lp.bottomMargin = mv
                    lp.leftMargin = mh
                    lp.rightMargin = mh
                    binding.root.layoutParams = lp
                }
            }

            val padH = scaledPx(if (tvMode) R.dimen.following_container_padding_h_tv else R.dimen.following_container_padding_h)
            if (binding.container.paddingLeft != padH || binding.container.paddingRight != padH) {
                binding.container.setPadding(
                    padH,
                    binding.container.paddingTop,
                    padH,
                    binding.container.paddingBottom,
                )
            }

            val selectedWidth = scaledPx(if (tvMode) R.dimen.following_selected_width_tv else R.dimen.following_selected_width)
            val selectedHeight = scaledPx(if (tvMode) R.dimen.following_selected_height_tv else R.dimen.following_selected_height)
            val selectedMarginEnd =
                scaledPx(if (tvMode) R.dimen.following_selected_margin_end_tv else R.dimen.following_selected_margin_end)
            val selectedLp = binding.vSelected.layoutParams as? ViewGroup.MarginLayoutParams
            if (selectedLp != null) {
                if (selectedLp.width != selectedWidth || selectedLp.height != selectedHeight || selectedLp.marginEnd != selectedMarginEnd) {
                    selectedLp.width = selectedWidth
                    selectedLp.height = selectedHeight
                    selectedLp.marginEnd = selectedMarginEnd
                    binding.vSelected.layoutParams = selectedLp
                }
            }

            val avatarSize = scaledPx(if (tvMode) R.dimen.following_avatar_size_tv else R.dimen.following_avatar_size).coerceAtLeast(1)
            val avatarLp = binding.ivAvatar.layoutParams
            if (avatarLp.width != avatarSize || avatarLp.height != avatarSize) {
                avatarLp.width = avatarSize
                avatarLp.height = avatarSize
                binding.ivAvatar.layoutParams = avatarLp
            }

            val nameLp = binding.tvName.layoutParams as? ViewGroup.MarginLayoutParams
            if (nameLp != null) {
                val ms = scaledPx(if (tvMode) R.dimen.following_name_margin_start_tv else R.dimen.following_name_margin_start)
                if (nameLp.marginStart != ms || nameLp.height != avatarSize) {
                    nameLp.marginStart = ms
                    nameLp.height = avatarSize
                    binding.tvName.layoutParams = nameLp
                }
            }
            binding.tvName.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                scaledPxF(if (tvMode) R.dimen.following_name_text_size_tv else R.dimen.following_name_text_size),
            )
        }
    }

    companion object {
        const val MID_ALL: Long = -1L
    }
}
