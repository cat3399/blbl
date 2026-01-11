package blbl.cat3399.feature.live

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.databinding.ItemLiveChatBinding

class LiveChatAdapter : RecyclerView.Adapter<LiveChatAdapter.Vh>() {
    data class Item(
        val title: String,
        val body: String,
    )

    private val items = ArrayList<Item>()

    fun submit(list: List<Item>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
        val binding = ItemLiveChatBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Vh(binding)
    }

    override fun onBindViewHolder(holder: Vh, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    class Vh(private val binding: ItemLiveChatBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: Item) {
            binding.tvTitle.text = item.title
            binding.tvBody.text = item.body
        }
    }
}

