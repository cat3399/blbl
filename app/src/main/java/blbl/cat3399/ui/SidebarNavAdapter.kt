package blbl.cat3399.ui

import android.content.res.ColorStateList
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.R
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.ui.ThemeColor
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

    fun selectedNavId(): Int = selectedId

    fun matches(
        list: List<NavItem>,
        selectedId: Int,
    ): Boolean = items == list && this.selectedId == selectedId

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
        fun bind(
            item: NavItem,
            selected: Boolean,
            showLabelsAlways: Boolean,
            onClick: () -> Unit,
        ) {
            binding.ivIcon.setImageResource(item.iconRes)
            binding.tvLabel.text = item.title
            binding.tvLabel.visibility = if (showLabelsAlways || selected) View.VISIBLE else View.GONE
            val ctx = binding.root.context
            binding.card.setCardBackgroundColor(
                if (selected) ThemeColor.resolve(ctx, R.attr.blblAccentContainer, R.color.blbl_surface) else 0x00000000,
            )
            binding.card.isSelected = selected
            val iconTint =
                if (selected) {
                    ThemeColor.resolve(ctx, R.attr.blblAccent, R.color.blbl_purple)
                } else {
                    ThemeColor.resolve(ctx, R.attr.blblOnPageBackdropSecondary, R.color.blbl_text_secondary)
                }
            ImageViewCompat.setImageTintList(binding.ivIcon, ColorStateList.valueOf(iconTint))

            val heightRes =
                when {
                    showLabelsAlways -> R.dimen.sidebar_nav_item_height_labeled
                    selected -> R.dimen.sidebar_nav_item_height_selected
                    else -> R.dimen.sidebar_nav_item_height_default
                }
            val heightPx = binding.root.resources.getDimensionPixelSize(heightRes).coerceAtLeast(1)
            val lp = binding.card.layoutParams
            if (lp.height != heightPx) {
                lp.height = heightPx
                binding.card.layoutParams = lp
            }
            binding.root.setOnClickListener { onClick() }
        }
    }

    companion object {
        const val ID_SEARCH = 0
        const val ID_HOME = 1
        const val ID_CATEGORY = 2
        const val ID_DYNAMIC = 3
        const val ID_LIVE = 4
        const val ID_MY = 5
        const val ID_CUSTOM = 6
    }
}
