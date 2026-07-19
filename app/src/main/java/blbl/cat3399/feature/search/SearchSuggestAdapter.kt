package blbl.cat3399.feature.search

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.core.ui.cloneInUserScale
import blbl.cat3399.databinding.ItemSearchSuggestBinding

class SearchSuggestAdapter(
    private val onClick: (String) -> Unit,
    private val onHistoryLongClick: (SearchSuggestionItem, Int) -> Boolean,
) : RecyclerView.Adapter<SearchSuggestAdapter.Vh>() {
    private val items = ArrayList<SearchSuggestionItem>()

    init {
        setHasStableIds(true)
    }

    fun invalidateSizing() {
        if (itemCount <= 0) return
        notifyItemRangeChanged(0, itemCount)
    }

    fun submit(list: List<SearchSuggestionItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemId(position: Int): Long = items[position].keyword.lowercase().hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
        val binding =
            ItemSearchSuggestBinding.inflate(
                LayoutInflater.from(parent.context).cloneInUserScale(parent.context),
                parent,
                false,
            )
        return Vh(binding)
    }

    override fun onBindViewHolder(holder: Vh, position: Int) {
        holder.bind(items[position], onClick, onHistoryLongClick)
    }

    override fun getItemCount(): Int = items.size

    class Vh(private val binding: ItemSearchSuggestBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(
            item: SearchSuggestionItem,
            onClick: (String) -> Unit,
            onHistoryLongClick: (SearchSuggestionItem, Int) -> Boolean,
        ) {
            binding.tvKeyword.text = item.keyword
            binding.root.setOnClickListener { onClick(item.keyword) }
            binding.root.setOnLongClickListener(
                if (item.isHistory) {
                    View.OnLongClickListener {
                        val position = bindingAdapterPosition
                        position != RecyclerView.NO_POSITION && onHistoryLongClick(item, position)
                    }
                } else {
                    null
                },
            )
        }
    }
}

data class SearchSuggestionItem(
    val keyword: String,
    val isHistory: Boolean,
)
