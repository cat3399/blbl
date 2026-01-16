package blbl.cat3399.feature.my

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.core.image.ImageLoader
import blbl.cat3399.core.image.ImageUrl
import blbl.cat3399.core.model.BangumiEpisode
import blbl.cat3399.core.util.pgcAccessBadgeTextOf
import blbl.cat3399.databinding.ItemBangumiEpisodeBinding

class BangumiEpisodeAdapter(
    private val onClick: (BangumiEpisode) -> Unit,
) : RecyclerView.Adapter<BangumiEpisodeAdapter.Vh>() {
    private val items = ArrayList<BangumiEpisode>()

    init {
        setHasStableIds(true)
    }

    fun submit(list: List<BangumiEpisode>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemId(position: Int): Long = items[position].epId

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
        val binding = ItemBangumiEpisodeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Vh(binding)
    }

    override fun onBindViewHolder(holder: Vh, position: Int) = holder.bind(items[position], onClick)

    override fun getItemCount(): Int = items.size

    class Vh(private val binding: ItemBangumiEpisodeBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: BangumiEpisode, onClick: (BangumiEpisode) -> Unit) {
            val title = item.title.trim().takeIf { it.isNotBlank() } ?: "-"
            binding.tvTitle.text = "第${title}话"
            ImageLoader.loadInto(binding.ivCover, ImageUrl.cover(item.coverUrl))

            val badgeText = pgcAccessBadgeTextOf(item.badge)
            binding.tvAccessBadgeText.isVisible = badgeText != null
            binding.tvAccessBadgeText.text = badgeText.orEmpty()

            binding.root.setOnClickListener { onClick(item) }
        }
    }
}
