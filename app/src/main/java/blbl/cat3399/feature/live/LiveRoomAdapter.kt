package blbl.cat3399.feature.live

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.core.image.ImageLoader
import blbl.cat3399.core.image.ImageUrl
import blbl.cat3399.core.model.LiveRoomCard
import blbl.cat3399.core.util.Format
import blbl.cat3399.databinding.ItemLiveCardBinding

class LiveRoomAdapter(
    private val onClick: (LiveRoomCard) -> Unit,
) : RecyclerView.Adapter<LiveRoomAdapter.Vh>() {
    private val items = ArrayList<LiveRoomCard>()

    init {
        setHasStableIds(true)
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

    override fun onBindViewHolder(holder: Vh, position: Int) = holder.bind(items[position], onClick)

    override fun getItemCount(): Int = items.size

    class Vh(private val binding: ItemLiveCardBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: LiveRoomCard, onClick: (LiveRoomCard) -> Unit) {
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
    }
}

