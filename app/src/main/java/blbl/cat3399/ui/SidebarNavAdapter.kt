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
            binding.ivIcon.setImageResource(item.iconRes)
            binding.tvLabel.text = item.title
            binding.tvLabel.visibility = if (showLabelsAlways || selected) View.VISIBLE else View.GONE
            binding.card.setCardBackgroundColor(
                if (selected) ContextCompat.getColor(binding.root.context, R.color.blbl_surface) else 0x00000000,
            )
            binding.card.isSelected = selected
            val iconTint = if (selected) R.color.blbl_purple else R.color.blbl_text_secondary
            binding.ivIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(binding.root.context, iconTint))

            val heightDp =
                when {
                    showLabelsAlways -> 52f
                    selected -> 56f
                    else -> 32f
                }
            val lp = binding.card.layoutParams
            lp.height = dp(binding.root, heightDp)
            binding.card.layoutParams = lp
            binding.root.setOnClickListener { onClick() }
        }

        private fun dp(view: View, v: Float): Int =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, view.resources.displayMetrics).toInt()
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
