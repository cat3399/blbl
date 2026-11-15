package blbl.cat3399.feature.settings

import android.view.LayoutInflater
import android.view.ViewGroup
import blbl.cat3399.R
import blbl.cat3399.core.ui.ThemeColor
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.databinding.ItemSettingsLeftBinding

class SettingsLeftAdapter(
    private val onClick: (Int) -> Unit,
) : RecyclerView.Adapter<SettingsLeftAdapter.Vh>() {
    private val items = ArrayList<String>()
    private var selected = 0

    init {
        setHasStableIds(true)
    }

    fun submit(list: List<String>, selected: Int) {
        items.clear()
        items.addAll(list)
        this.selected = selected.coerceIn(0, (items.lastIndex).coerceAtLeast(0))
        notifyDataSetChanged()
    }

    fun setSelected(position: Int) {
        if (position !in items.indices || position == selected) return
        val oldSelected = selected
        selected = position
        if (oldSelected in items.indices) notifyItemChanged(oldSelected)
        notifyItemChanged(position)
    }

    override fun getItemId(position: Int): Long {
        return items.getOrNull(position)?.hashCode()?.toLong() ?: RecyclerView.NO_ID
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
        val binding = ItemSettingsLeftBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Vh(binding)
    }

    override fun onBindViewHolder(holder: Vh, position: Int) {
        holder.bind(items[position], position == selected) {
            val clickedPosition = holder.bindingAdapterPosition
            if (clickedPosition == RecyclerView.NO_POSITION) return@bind

            onClick(clickedPosition)
        }
    }

    override fun getItemCount(): Int = items.size

    class Vh(private val binding: ItemSettingsLeftBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(text: String, selected: Boolean, onClick: () -> Unit) {
            binding.tvTitle.text = text
            binding.root.isSelected = selected
            val ctx = binding.root.context
            binding.root.setCardBackgroundColor(
                if (selected) {
                    ThemeColor.resolve(
                        context = ctx,
                        attr = R.attr.blblAccentContainer,
                        fallbackRes = R.color.blbl_accent_violet_container,
                    )
                } else {
                    0x00000000
                },
            )
            binding.root.setOnClickListener { onClick() }
        }
    }
}
