package blbl.cat3399.feature.my

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.core.image.ImageLoader
import blbl.cat3399.core.image.ImageUrl
import blbl.cat3399.core.model.BangumiSeason
import blbl.cat3399.core.util.pgcAccessBadgeTextOf
import blbl.cat3399.databinding.ItemBangumiFollowBinding

class BangumiFollowAdapter(
    private val onClick: (position: Int, season: BangumiSeason) -> Unit,
) : RecyclerView.Adapter<BangumiFollowAdapter.Vh>() {
    private val items = ArrayList<BangumiSeason>()

    init {
        setHasStableIds(true)
    }

    fun submit(list: List<BangumiSeason>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun append(list: List<BangumiSeason>) {
        if (list.isEmpty()) return
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    override fun getItemId(position: Int): Long = items[position].seasonId

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
        val binding = ItemBangumiFollowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Vh(binding)
    }

    override fun onBindViewHolder(holder: Vh, position: Int) = holder.bind(items[position], onClick)

    override fun getItemCount(): Int = items.size

    class Vh(private val binding: ItemBangumiFollowBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: BangumiSeason, onClick: (position: Int, season: BangumiSeason) -> Unit) {
            binding.tvTitle.text = item.title

            val badgeText = pgcAccessBadgeTextOf(item.badgeEp, item.badge)
            binding.tvAccessBadgeText.isVisible = badgeText != null
            binding.tvAccessBadgeText.text = badgeText.orEmpty()

            val metaParts =
                buildList {
                    item.seasonTypeName?.let { add(it) }
                    val progress =
                        item.progressText?.takeIf { it.isNotBlank() }
                            ?: item.lastEpIndex?.let { "看到第${it}话" }
                    progress?.let { add(it) }
                    when {
                        item.isFinish == true -> add("已完结")
                        item.newestEpIndex != null -> add("更新至${item.newestEpIndex}话")
                        item.totalCount != null -> add("共${item.totalCount}话")
                    }
                }
            val subtitle = metaParts.joinToString(" · ")
            binding.tvSubtitle.text = subtitle
            binding.tvSubtitle.isVisible = subtitle.isNotBlank()
            ImageLoader.loadInto(binding.ivCover, ImageUrl.poster(item.coverUrl))

            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: return@setOnClickListener
                onClick(pos, item)
            }
        }
    }
}
