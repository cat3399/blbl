package blbl.cat3399.feature.my

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.content.Intent
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.image.ImageLoader
import blbl.cat3399.core.image.ImageUrl
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.BangumiEpisode
import blbl.cat3399.core.util.Format
import blbl.cat3399.databinding.FragmentMyBangumiDetailBinding
import blbl.cat3399.feature.player.PlayerActivity
import blbl.cat3399.feature.player.PlayerPlaylistItem
import blbl.cat3399.feature.player.PlayerPlaylistStore
import blbl.cat3399.ui.RefreshKeyHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MyBangumiDetailFragment : Fragment(), RefreshKeyHandler {
    private var _binding: FragmentMyBangumiDetailBinding? = null
    private val binding get() = _binding!!

    private val seasonId: Long by lazy { requireArguments().getLong(ARG_SEASON_ID) }
    private val isDrama: Boolean by lazy { requireArguments().getBoolean(ARG_IS_DRAMA) }
    private val continueEpIdArg: Long? by lazy { requireArguments().getLong(ARG_CONTINUE_EP_ID, -1L).takeIf { it > 0 } }
    private val continueEpIndexArg: Int? by lazy { requireArguments().getInt(ARG_CONTINUE_EP_INDEX, -1).takeIf { it > 0 } }

    private lateinit var epAdapter: BangumiEpisodeAdapter
    private var currentEpisodes: List<BangumiEpisode> = emptyList()
    private var continueEpisode: BangumiEpisode? = null
    private var pendingAutoFocusFirstEpisode: Boolean = true
    private var autoFocusAttempts: Int = 0
    private var epDataObserver: RecyclerView.AdapterDataObserver? = null
    private var pendingAutoFocusPrimary: Boolean = true
    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingAutoFocusFirstEpisode = savedInstanceState == null
        pendingAutoFocusPrimary = savedInstanceState == null
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMyBangumiDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStackImmediate() }
        binding.btnSecondary.text = if (isDrama) "已追剧" else "已追番"

        epAdapter =
            BangumiEpisodeAdapter { ep, pos ->
                playEpisode(ep, pos)
            }
        binding.recyclerEpisodes.adapter = epAdapter
        binding.recyclerEpisodes.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        epDataObserver =
            object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() {
                    tryAutoFocusFirstEpisode()
                    tryAutoFocusPrimary()
                }
            }.also { epAdapter.registerAdapterDataObserver(it) }

        binding.btnPrimary.setOnClickListener {
            val ep = continueEpisode ?: currentEpisodes.firstOrNull()
            if (ep == null) {
                Toast.makeText(requireContext(), "暂无可播放剧集", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val pos = currentEpisodes.indexOfFirst { it.epId == ep.epId }.takeIf { it >= 0 } ?: 0
            playEpisode(ep, pos)
        }
        binding.btnSecondary.setOnClickListener {
            Toast.makeText(requireContext(), "暂不支持操作", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        tryAutoFocusPrimary()
        load()
    }

    override fun handleRefreshKey(): Boolean {
        if (!isResumed) return false
        if (_binding == null) return false
        load()
        return true
    }

    private fun tryAutoFocusPrimary() {
        if (!pendingAutoFocusPrimary) return
        if (!isResumed) return
        val b = _binding ?: return
        val focused = activity?.currentFocus
        if (focused != null && isDescendantOf(focused, b.root) && focused != b.btnBack) {
            pendingAutoFocusPrimary = false
            return
        }
        b.root.post {
            val bb = _binding ?: return@post
            if (!isResumed) return@post
            if (!pendingAutoFocusPrimary) return@post
            val focused2 = activity?.currentFocus
            if (focused2 != null && isDescendantOf(focused2, bb.root) && focused2 != bb.btnBack) {
                pendingAutoFocusPrimary = false
                return@post
            }
            if (bb.btnPrimary.requestFocus()) {
                pendingAutoFocusPrimary = false
            }
        }
    }

    private fun tryAutoFocusFirstEpisode() {
        if (!pendingAutoFocusFirstEpisode) return
        if (!isResumed) return
        val b = _binding ?: return
        if (!this::epAdapter.isInitialized) return
        if (epAdapter.itemCount <= 0) return

        val recycler = b.recyclerEpisodes
        val focused = activity?.currentFocus
        if (focused != null && isDescendantOf(focused, recycler)) {
            pendingAutoFocusFirstEpisode = false
            return
        }
        if (continueEpisode != null) {
            pendingAutoFocusFirstEpisode = false
            return
        }

        autoFocusAttempts++
        if (autoFocusAttempts > 60) {
            pendingAutoFocusFirstEpisode = false
            return
        }

        recycler.post {
            val bb = _binding ?: return@post
            if (!isResumed) return@post
            if (!pendingAutoFocusFirstEpisode) return@post
            if (epAdapter.itemCount <= 0) return@post

            val r = bb.recyclerEpisodes
            val focused2 = activity?.currentFocus
            if (focused2 != null && isDescendantOf(focused2, r)) {
                pendingAutoFocusFirstEpisode = false
                return@post
            }

            val success = r.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() == true
            if (success) {
                pendingAutoFocusFirstEpisode = false
                return@post
            }

            r.scrollToPosition(0)
            r.postDelayed({ tryAutoFocusFirstEpisode() }, 16)
        }
    }

    private fun load() {
        loadJob?.cancel()
        loadJob =
            viewLifecycleOwner.lifecycleScope.launch {
            try {
                val detail = BiliApi.bangumiSeasonDetail(seasonId = seasonId)
                val b = _binding ?: return@launch
                b.tvTitle.text = detail.title
                b.tvDesc.text = detail.evaluate.orEmpty()

                val metaParts = buildList {
                    detail.subtitle?.takeIf { it.isNotBlank() }?.let { add(it) }
                    detail.ratingScore?.let { add(String.format("%.1f分", it)) }
                    detail.views?.let { add("${Format.count(it)}次观看") }
                    detail.danmaku?.let { add("${Format.count(it)}条弹幕") }
                }
                b.tvMeta.text = metaParts.joinToString(" | ")
                ImageLoader.loadInto(b.ivCover, ImageUrl.poster(detail.coverUrl))
                currentEpisodes = detail.episodes
                continueEpisode =
                    (continueEpIdArg ?: detail.progressLastEpId)?.let { id ->
                        detail.episodes.firstOrNull { it.epId == id }
                    } ?: continueEpIndexArg?.let { idx ->
                        detail.episodes.firstOrNull { it.title.trim() == idx.toString() }
                    }
                epAdapter.submit(detail.episodes)
                tryAutoFocusFirstEpisode()
                tryAutoFocusPrimary()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e("MyBangumiDetail", "load failed seasonId=$seasonId", t)
                context?.let { Toast.makeText(it, "加载失败，可查看 Logcat(标签 BLBL)", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun playEpisode(ep: BangumiEpisode, pos: Int) {
        val bvid = ep.bvid.orEmpty()
        val cid = ep.cid ?: -1L
        if (bvid.isBlank() || cid <= 0) {
            Toast.makeText(requireContext(), "缺少播放信息（bvid/cid）", Toast.LENGTH_SHORT).show()
            return
        }
        val allItems =
            currentEpisodes.map {
                PlayerPlaylistItem(
                    bvid = it.bvid.orEmpty(),
                    cid = it.cid,
                    epId = it.epId,
                    aid = it.aid,
                    title = it.title,
                )
            }
        val playlistItems = allItems.filter { it.bvid.isNotBlank() }
        val playlistIndex =
            playlistItems.indexOfFirst { it.epId == ep.epId }
                .takeIf { it >= 0 }
                ?: pos
        val token = PlayerPlaylistStore.put(items = playlistItems, index = playlistIndex, source = "Bangumi:$seasonId")
        startActivity(
            Intent(requireContext(), PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_BVID, bvid)
                .putExtra(PlayerActivity.EXTRA_CID, cid)
                .putExtra(PlayerActivity.EXTRA_EP_ID, ep.epId)
                .apply { ep.aid?.let { putExtra(PlayerActivity.EXTRA_AID, it) } }
                .putExtra(PlayerActivity.EXTRA_PLAYLIST_TOKEN, token)
                .putExtra(PlayerActivity.EXTRA_PLAYLIST_INDEX, playlistIndex),
        )
    }

    override fun onDestroyView() {
        loadJob?.cancel()
        loadJob = null
        if (this::epAdapter.isInitialized) {
            epDataObserver?.let { epAdapter.unregisterAdapterDataObserver(it) }
        }
        epDataObserver = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_SEASON_ID = "season_id"
        private const val ARG_IS_DRAMA = "is_drama"
        private const val ARG_CONTINUE_EP_ID = "continue_ep_id"
        private const val ARG_CONTINUE_EP_INDEX = "continue_ep_index"

        fun newInstance(
            seasonId: Long,
            isDrama: Boolean,
            continueEpId: Long?,
            continueEpIndex: Int?,
        ): MyBangumiDetailFragment =
            MyBangumiDetailFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_SEASON_ID, seasonId)
                    putBoolean(ARG_IS_DRAMA, isDrama)
                    continueEpId?.let { putLong(ARG_CONTINUE_EP_ID, it) }
                    continueEpIndex?.let { putInt(ARG_CONTINUE_EP_INDEX, it) }
                }
            }
    }
}
