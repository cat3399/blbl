package blbl.cat3399.feature.live

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import blbl.cat3399.databinding.FragmentLiveAreaDetailBinding

class LiveAreaDetailFragment : Fragment() {
    private var _binding: FragmentLiveAreaDetailBinding? = null
    private val binding get() = _binding!!

    private val parentAreaId: Int by lazy { requireArguments().getInt(ARG_PARENT_AREA_ID, 0) }
    private val areaId: Int by lazy { requireArguments().getInt(ARG_AREA_ID, 0) }
    private val parentTitle: String by lazy { requireArguments().getString(ARG_PARENT_TITLE).orEmpty() }
    private val areaTitle: String by lazy { requireArguments().getString(ARG_AREA_TITLE).orEmpty() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLiveAreaDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.tvTitle.text =
            when {
                parentTitle.isBlank() -> areaTitle
                areaTitle.isBlank() -> parentTitle
                parentTitle == areaTitle -> areaTitle
                else -> "$parentTitle · $areaTitle"
            }

        if (savedInstanceState == null) {
            childFragmentManager.beginTransaction()
                .setReorderingAllowed(true)
                .replace(
                    binding.contentContainer.id,
                    LiveGridFragment.newArea(
                        parentAreaId = parentAreaId,
                        areaId = areaId,
                        title = areaTitle.ifBlank { parentTitle },
                        enableTabFocus = false,
                    ),
                )
                .commit()

            // Match "MyFavFolderDetail": enter detail then focus first content card automatically.
            binding.contentContainer.post {
                if (_binding == null || !isAdded) return@post
                runCatching { childFragmentManager.executePendingTransactions() }
                val page = childFragmentManager.findFragmentById(binding.contentContainer.id)
                (page as? LivePageFocusTarget)?.requestFocusFirstCardFromContentSwitch()
                    ?: (childFragmentManager.fragments.firstOrNull { it is LivePageFocusTarget } as? LivePageFocusTarget)
                        ?.requestFocusFirstCardFromContentSwitch()
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_PARENT_AREA_ID = "parent_area_id"
        private const val ARG_PARENT_TITLE = "parent_title"
        private const val ARG_AREA_ID = "area_id"
        private const val ARG_AREA_TITLE = "area_title"

        fun newInstance(parentAreaId: Int, parentTitle: String, areaId: Int, areaTitle: String): LiveAreaDetailFragment =
            LiveAreaDetailFragment().apply {
                arguments =
                    Bundle().apply {
                        putInt(ARG_PARENT_AREA_ID, parentAreaId)
                        putString(ARG_PARENT_TITLE, parentTitle)
                        putInt(ARG_AREA_ID, areaId)
                        putString(ARG_AREA_TITLE, areaTitle)
                    }
            }
    }
}
