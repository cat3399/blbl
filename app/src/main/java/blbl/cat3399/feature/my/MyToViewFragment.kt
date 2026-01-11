package blbl.cat3399.feature.my

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.FocusFinder
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.databinding.FragmentVideoGridBinding
import blbl.cat3399.feature.player.PlayerActivity
import blbl.cat3399.feature.video.VideoCardAdapter
import kotlinx.coroutines.launch

class MyToViewFragment : Fragment() {
    private var _binding: FragmentVideoGridBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: VideoCardAdapter
    private var initialLoadTriggered: Boolean = false
    private var requestToken: Int = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVideoGridBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (!::adapter.isInitialized) {
            adapter =
                VideoCardAdapter { card ->
                    startActivity(
                        Intent(requireContext(), PlayerActivity::class.java)
                            .putExtra(PlayerActivity.EXTRA_BVID, card.bvid)
                            .putExtra(PlayerActivity.EXTRA_CID, card.cid ?: -1L),
                    )
                }
        }
        binding.recycler.adapter = adapter
        binding.recycler.setHasFixedSize(true)
        binding.recycler.layoutManager = GridLayoutManager(requireContext(), spanCountForWidth(resources))
        (binding.recycler.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        binding.recycler.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    view.setOnKeyListener { v, keyCode, event ->
                        if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP -> {
                                if (!binding.recycler.canScrollVertically(-1)) {
                                    val lm = binding.recycler.layoutManager as? GridLayoutManager ?: return@setOnKeyListener false
                                    val holder = binding.recycler.findContainingViewHolder(v) ?: return@setOnKeyListener false
                                    val pos = holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: return@setOnKeyListener false
                                    if (pos < lm.spanCount) {
                                        focusSelectedMyTabIfAvailable()
                                        return@setOnKeyListener true
                                    }
                                }
                                false
                            }

                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                val itemView = binding.recycler.findContainingItemView(v) ?: return@setOnKeyListener false
                                val next = FocusFinder.getInstance().findNextFocus(binding.recycler, itemView, View.FOCUS_LEFT)
                                if (next == null || !isDescendantOf(next, binding.recycler)) {
                                    val switched = switchToPrevMyTabIfAvailable()
                                    return@setOnKeyListener switched
                                }
                                false
                            }

                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                val itemView = binding.recycler.findContainingItemView(v) ?: return@setOnKeyListener false
                                val next = FocusFinder.getInstance().findNextFocus(binding.recycler, itemView, View.FOCUS_RIGHT)
                                if (next == null || !isDescendantOf(next, binding.recycler)) {
                                    if (switchToNextMyTabIfAvailable()) return@setOnKeyListener true
                                    return@setOnKeyListener true
                                }
                                false
                            }

                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                val itemView = binding.recycler.findContainingItemView(v) ?: return@setOnKeyListener false
                                val next = FocusFinder.getInstance().findNextFocus(binding.recycler, itemView, View.FOCUS_DOWN)
                                if (next == null || !isDescendantOf(next, binding.recycler)) {
                                    return@setOnKeyListener true
                                }
                                false
                            }

                            else -> false
                        }
                    }
                }

                override fun onChildViewDetachedFromWindow(view: View) {
                    view.setOnKeyListener(null)
                }
            },
        )
        binding.swipeRefresh.setOnRefreshListener { reload() }
    }

    override fun onResume() {
        super.onResume()
        (binding.recycler.layoutManager as? GridLayoutManager)?.spanCount = spanCountForWidth(resources)
        maybeTriggerInitialLoad()
    }

    private fun maybeTriggerInitialLoad() {
        if (initialLoadTriggered) return
        if (!this::adapter.isInitialized) return
        if (adapter.itemCount != 0) {
            initialLoadTriggered = true
            return
        }
        if (binding.swipeRefresh.isRefreshing) return
        binding.swipeRefresh.isRefreshing = true
        reload()
        initialLoadTriggered = true
    }

    private fun reload() {
        val token = ++requestToken
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val list = BiliApi.toViewList()
                if (token != requestToken) return@launch
                adapter.submit(list)
            } catch (t: Throwable) {
                AppLog.e("MyToView", "load failed", t)
                Toast.makeText(requireContext(), "加载失败，可查看 Logcat(标签 BLBL)", Toast.LENGTH_SHORT).show()
            } finally {
                if (token == requestToken) binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    override fun onDestroyView() {
        initialLoadTriggered = false
        _binding = null
        super.onDestroyView()
    }
}
