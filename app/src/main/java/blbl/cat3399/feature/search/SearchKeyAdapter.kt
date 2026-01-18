package blbl.cat3399.feature.search

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.core.ui.UiScale
import blbl.cat3399.databinding.ItemSearchKeyBinding
import kotlin.math.roundToInt

class SearchKeyAdapter(
    private val onClick: (String) -> Unit,
) : RecyclerView.Adapter<SearchKeyAdapter.Vh>() {
    private val items = ArrayList<String>()
    private var tvMode: Boolean = false

    init {
        setHasStableIds(true)
    }

    fun setTvMode(enabled: Boolean) {
        tvMode = enabled
        notifyItemRangeChanged(0, itemCount)
    }

    fun submit(list: List<String>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemId(position: Int): Long = items[position].hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
        val binding = ItemSearchKeyBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Vh(binding)
    }

    override fun onBindViewHolder(holder: Vh, position: Int) {
        holder.bind(items[position], tvMode, onClick)
    }

    override fun getItemCount(): Int = items.size

    class Vh(private val binding: ItemSearchKeyBinding) : RecyclerView.ViewHolder(binding.root) {
        private var lastTvMode: Boolean? = null
        private var lastUiScale: Float? = null
        private var baseHeight: Int? = null
        private var baseMargins: IntArray? = null
        private var baseTextSizePx: Float? = null
        private var baseCornerRadiusPx: Float? = null
        private var baseStrokeWidthPx: Int? = null

        fun bind(label: String, tvMode: Boolean, onClick: (String) -> Unit) {
            val uiScale = UiScale.factor(binding.root.context, tvMode)
            if (lastTvMode != tvMode || lastUiScale != uiScale) {
                applySizing(uiScale)
                lastTvMode = tvMode
                lastUiScale = uiScale
            }
            binding.tvLabel.text = label
            binding.root.setOnClickListener { onClick(label) }
        }

        private fun applySizing(uiScale: Float) {
            val card = binding.root
            val lp = card.layoutParams
            if (lp != null) {
                val baseH = baseHeight ?: lp.height.also { baseHeight = it }
                if (baseH > 0) {
                    val h = (baseH * uiScale).roundToInt().coerceAtLeast(1)
                    if (lp.height != h) {
                        lp.height = h
                        card.layoutParams = lp
                    }
                }

                val mlp = lp as? MarginLayoutParams
                if (mlp != null) {
                    val base =
                        baseMargins
                            ?: intArrayOf(mlp.leftMargin, mlp.topMargin, mlp.rightMargin, mlp.bottomMargin).also { baseMargins = it }
                    val l = (base[0] * uiScale).roundToInt().coerceAtLeast(0)
                    val t = (base[1] * uiScale).roundToInt().coerceAtLeast(0)
                    val r = (base[2] * uiScale).roundToInt().coerceAtLeast(0)
                    val b = (base[3] * uiScale).roundToInt().coerceAtLeast(0)
                    if (mlp.leftMargin != l || mlp.topMargin != t || mlp.rightMargin != r || mlp.bottomMargin != b) {
                        mlp.setMargins(l, t, r, b)
                        card.layoutParams = mlp
                    }
                }
            }

            val baseText = baseTextSizePx ?: binding.tvLabel.textSize.also { baseTextSizePx = it }
            val textPx = (baseText * uiScale).coerceAtLeast(1f)
            binding.tvLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx)

            val baseRadius = baseCornerRadiusPx ?: card.radius.also { baseCornerRadiusPx = it }
            val radius = (baseRadius * uiScale).coerceAtLeast(0f)
            if (card.radius != radius) card.radius = radius

            val baseStroke = baseStrokeWidthPx ?: card.strokeWidth.also { baseStrokeWidthPx = it }
            val stroke = (baseStroke * uiScale).roundToInt().coerceAtLeast(0)
            if (card.strokeWidth != stroke) card.strokeWidth = stroke
        }
    }
}
