package blbl.cat3399.feature.player

import android.animation.AnimatorInflater
import android.os.Build
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.core.image.ImageLoader
import blbl.cat3399.core.image.ImageUrl
import blbl.cat3399.core.model.VideoCard
import blbl.cat3399.core.ui.cloneInUserScale
import blbl.cat3399.core.util.Format
import blbl.cat3399.databinding.ItemPlayerInfoRecommendBinding

internal class PlayerInfoRecommendAdapter(
    private val onClick: (VideoCard, Int) -> Unit,
) : RecyclerView.Adapter<PlayerInfoRecommendAdapter.Vh>() {
    private val items = ArrayList<VideoCard>()

    init {
        setHasStableIds(true)
    }

    fun submit(list: List<VideoCard>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemId(position: Int): Long = items[position].stableKey().hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
        val binding =
            ItemPlayerInfoRecommendBinding.inflate(
                LayoutInflater.from(parent.context).cloneInUserScale(parent.context),
                parent,
                false,
            )
        return Vh(binding)
    }

    override fun onBindViewHolder(holder: Vh, position: Int) {
        holder.bind(items[position], position, onClick)
    }

    override fun getItemCount(): Int = items.size

    internal class Vh(
        private val binding: ItemPlayerInfoRecommendBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        init {
            if (Build.VERSION.SDK_INT >= 21) {
                binding.root.stateListAnimator =
                    AnimatorInflater.loadStateListAnimator(binding.root.context, blbl.cat3399.R.animator.blbl_focus_scale)
            }
        }

        fun bind(
            item: VideoCard,
            position: Int,
            onClick: (VideoCard, Int) -> Unit,
        ) {
            binding.tvTitle.text = item.title
            binding.tvOwner.text = item.ownerName.ifBlank { "UP 主" }
            binding.ivViewIcon.isVisible = item.view != null
            binding.tvView.isVisible = item.view != null
            binding.ivDanmakuIcon.isVisible = item.danmaku != null
            binding.tvDanmaku.isVisible = item.danmaku != null
            binding.tvView.text = Format.count(item.view)
            binding.tvDanmaku.text = Format.count(item.danmaku)
            ImageLoader.loadInto(binding.ivCover, ImageUrl.cover(item.coverUrl))

            binding.root.setOnClickListener {
                val safePos = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: position
                onClick(item, safePos)
            }
        }
    }
}
