package blbl.cat3399.feature.settings

import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.core.io.CreateDocumentContract
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.BaseActivity
import blbl.cat3399.core.ui.FocusTreeUtils
import blbl.cat3399.core.ui.cloneInUserScale
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.core.ui.popup.PopupHost
import blbl.cat3399.databinding.ActivitySettingsBinding

class SettingsActivity : BaseActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val state = SettingsState()
    private lateinit var leftAdapter: SettingsLeftAdapter
    private lateinit var rightAdapter: SettingsEntryAdapter
    private lateinit var renderer: SettingsRenderer
    private lateinit var interactionHandler: SettingsInteractionHandler

    private val gaiaVgateLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (!this::interactionHandler.isInitialized) return@registerForActivityResult
            interactionHandler.onGaiaVgateResult(result)
        }

    private val exportDocumentLauncher =
        registerForActivityResult(CreateDocumentContract()) { uri ->
            if (!this::interactionHandler.isInitialized) return@registerForActivityResult
            interactionHandler.onExportDocumentSelected(uri)
        }

    private val importConfigLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (!this::interactionHandler.isInitialized) return@registerForActivityResult
            interactionHandler.onImportConfigSelected(uri)
        }

    private val sections =
        listOf(
            "通用设置",
            "页面设置",
            "播放设置",
            "弹幕设置",
            "关于应用",
            "设备信息",
            "其他设置",
        )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater.cloneInUserScale(this))
        setContentView(binding.root)
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)

        interactionHandler =
            SettingsInteractionHandler(
                activity = this,
                state = state,
                gaiaVgateLauncher = gaiaVgateLauncher,
                exportDocumentLauncher = exportDocumentLauncher,
                importConfigLauncher = importConfigLauncher,
            )

        binding.btnBack.setOnClickListener { finish() }

        leftAdapter = SettingsLeftAdapter { index -> renderer.showSection(index, keepScroll = false) }
        binding.recyclerLeft.layoutManager = LinearLayoutManager(this)
        binding.recyclerLeft.itemAnimator = null
        binding.recyclerLeft.adapter = leftAdapter
        leftAdapter.submit(sections, selected = 0)

        binding.recyclerRight.layoutManager = LinearLayoutManager(this)
        binding.recyclerRight.itemAnimator = null
        rightAdapter = SettingsEntryAdapter { entry -> interactionHandler.onEntryClicked(entry) }
        binding.recyclerRight.adapter = rightAdapter

        renderer =
            SettingsRenderer(
                activity = this,
                binding = binding,
                state = state,
                sections = sections,
                leftAdapter = leftAdapter,
                rightAdapter = rightAdapter,
                onSectionShown = { sectionName -> interactionHandler.onSectionShown(sectionName) },
            )
        interactionHandler.renderer = renderer

        renderer.installFocusListener()
        renderer.showSection(0)
    }

    override fun onResume() {
        super.onResume()
        renderer.ensureInitialFocus()
    }

    override fun onDestroy() {
        renderer.uninstallFocusListener()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
        if (hasFocus) renderer.restorePendingFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!this::renderer.isInitialized || !this::binding.isInitialized) return super.dispatchKeyEvent(event)

        val popupHost = PopupHost.peek(this)
        if (popupHost != null && popupHost.consumeBackLikeKeyEventIfNeeded(event)) {
            return true
        }

        val keyCode = event.keyCode
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            val focused = currentFocus
            val holder = focused?.let { binding.recyclerLeft.findContainingViewHolder(it) }
            val position = holder?.bindingAdapterPosition ?: RecyclerView.NO_POSITION
            if (position != RecyclerView.NO_POSITION) {
                if (event.action == KeyEvent.ACTION_DOWN && !event.isCanceled) {
                    renderer.enterSectionContent(position)
                }
                return true
            }
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            val focused = currentFocus
            val focusInContent = focused != null && FocusTreeUtils.isDescendantOf(focused, binding.recyclerRight)
            if (focusInContent) {
                if (event.action == KeyEvent.ACTION_DOWN && !event.isCanceled) {
                    renderer.focusSectionTab(state.currentSectionIndex)
                }
                return true
            }
        }
        if (isBackLikeKey(keyCode)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val focused = currentFocus
                val focusInContent = focused != null && FocusTreeUtils.isDescendantOf(focused, binding.recyclerRight)
                if (focusInContent) {
                    renderer.focusSectionTab(state.currentSectionIndex)
                } else {
                    finish()
                }
            }
            return true
        }

        if (event.action == KeyEvent.ACTION_DOWN && currentFocus == null && renderer.isNavKey(keyCode)) {
            renderer.ensureInitialFocus()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isBackLikeKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_BACK ||
            keyCode == KeyEvent.KEYCODE_ESCAPE ||
            keyCode == KeyEvent.KEYCODE_BUTTON_B
    }
}
