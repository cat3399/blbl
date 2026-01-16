package blbl.cat3399.ui

import android.content.res.ColorStateList
import android.os.SystemClock
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.R
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.databinding.ItemSidebarNavBinding

class SidebarNavAdapter(
    private val onClick: (NavItem) -> Boolean,
) : RecyclerView.Adapter<SidebarNavAdapter.Vh>() {
    data class NavItem(
        val id: Int,
        val title: String,
        val iconRes: Int,
    )

    private val items = ArrayList<NavItem>()
    private var selectedId: Int = ID_HOME
    private var showLabelsAlways: Boolean = false

    fun submit(list: List<NavItem>, selectedId: Int) {
        items.clear()
        items.addAll(list)
        this.selectedId = selectedId
        notifyDataSetChanged()
    }

    fun setShowLabelsAlways(enabled: Boolean) {
        if (showLabelsAlways == enabled) return
        showLabelsAlways = enabled
        notifyDataSetChanged()
    }

    fun select(id: Int, trigger: Boolean) {
        if (selectedId == id) {
            if (trigger) items.firstOrNull { it.id == id }?.let { onClick(it) }
            return
        }
        val prevId = selectedId
        selectedId = id
        AppLog.d(
            "Nav",
            "select prev=$prevId new=$id trigger=$trigger t=${SystemClock.uptimeMillis()}",
        )

        val prevPos = items.indexOfFirst { it.id == prevId }
        val newPos = items.indexOfFirst { it.id == id }
        if (prevPos >= 0) notifyItemChanged(prevPos)
        if (newPos >= 0) notifyItemChanged(newPos)

        if (trigger) items.firstOrNull { it.id == id }?.let { onClick(it) }
    }

    fun selectedAdapterPosition(): Int = items.indexOfFirst { it.id == selectedId }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
        val binding = ItemSidebarNavBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Vh(binding)
    }

    override fun onBindViewHolder(holder: Vh, position: Int) {
        val item = items[position]
        val selected = item.id == selectedId
        AppLog.d(
            "Nav",
            "bind pos=$position id=${item.id} selected=$selected labels=$showLabelsAlways t=${SystemClock.uptimeMillis()}",
        )
        holder.bind(item, selected, showLabelsAlways) {
            val handled = onClick(item)
            if (handled) select(item.id, trigger = false)
        }
    }

    override fun getItemCount(): Int = items.size

    class Vh(private val binding: ItemSidebarNavBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: NavItem, selected: Boolean, showLabelsAlways: Boolean, onClick: () -> Unit) {
            val tvSizing = showLabelsAlways
            applySizing(tvSizing)

            binding.ivIcon.setImageResource(item.iconRes)
            binding.tvLabel.text = item.title
            binding.tvLabel.visibility = if (showLabelsAlways || selected) View.VISIBLE else View.GONE
            binding.card.setCardBackgroundColor(
                if (selected) ContextCompat.getColor(binding.root.context, R.color.blbl_surface) else 0x00000000,
            )
            binding.card.isSelected = selected
            val iconTint = if (selected) R.color.blbl_purple else R.color.blbl_text_secondary
            binding.ivIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(binding.root.context, iconTint))

            val heightPx =
                binding.root.resources.getDimensionPixelSize(
                    when {
                        showLabelsAlways ->
                            if (tvSizing) R.dimen.sidebar_nav_item_height_labeled_tv else R.dimen.sidebar_nav_item_height_labeled

                        selected ->
                            if (tvSizing) R.dimen.sidebar_nav_item_height_selected_tv else R.dimen.sidebar_nav_item_height_selected

                        else ->
                            if (tvSizing) R.dimen.sidebar_nav_item_height_default_tv else R.dimen.sidebar_nav_item_height_default
                    },
                )
            val lp = binding.card.layoutParams
            lp.height = heightPx
            binding.card.layoutParams = lp
            binding.root.setOnClickListener { onClick() }
        }

        private fun applySizing(tvSizing: Boolean) {
            fun px(id: Int): Int = binding.root.resources.getDimensionPixelSize(id)
            fun pxF(id: Int): Float = binding.root.resources.getDimension(id)

            val iconSize = px(if (tvSizing) R.dimen.sidebar_nav_icon_size_tv else R.dimen.sidebar_nav_icon_size)
            val iconLp = binding.ivIcon.layoutParams
            if (iconLp.width != iconSize || iconLp.height != iconSize) {
                iconLp.width = iconSize
                iconLp.height = iconSize
                binding.ivIcon.layoutParams = iconLp
            }

            binding.tvLabel.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                pxF(if (tvSizing) R.dimen.sidebar_nav_label_text_size_tv else R.dimen.sidebar_nav_label_text_size),
            )

            (binding.tvLabel.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                val mt = px(if (tvSizing) R.dimen.sidebar_nav_label_margin_top_tv else R.dimen.sidebar_nav_label_margin_top)
                if (lp.topMargin != mt) {
                    lp.topMargin = mt
                    binding.tvLabel.layoutParams = lp
                }
            }

            val padV = px(if (tvSizing) R.dimen.sidebar_nav_container_padding_v_tv else R.dimen.sidebar_nav_container_padding_v)
            if (binding.container.paddingTop != padV || binding.container.paddingBottom != padV) {
                binding.container.setPadding(
                    binding.container.paddingLeft,
                    padV,
                    binding.container.paddingRight,
                    padV,
                )
            }

            (binding.card.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                val mv = px(if (tvSizing) R.dimen.sidebar_nav_card_margin_v_tv else R.dimen.sidebar_nav_card_margin_v)
                val mh = px(if (tvSizing) R.dimen.sidebar_nav_card_margin_h_tv else R.dimen.sidebar_nav_card_margin_h)
                if (lp.topMargin != mv || lp.bottomMargin != mv || lp.leftMargin != mh || lp.rightMargin != mh) {
                    lp.topMargin = mv
                    lp.bottomMargin = mv
                    lp.leftMargin = mh
                    lp.rightMargin = mh
                    binding.card.layoutParams = lp
                }
            }
        }
    }

    companion object {
        const val ID_SEARCH = 0
        const val ID_HOME = 1
        const val ID_CATEGORY = 2
        const val ID_DYNAMIC = 3
        const val ID_LIVE = 4
        const val ID_MY = 5
    }
}
