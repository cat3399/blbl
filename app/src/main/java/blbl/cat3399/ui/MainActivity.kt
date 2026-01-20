package blbl.cat3399.ui

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import blbl.cat3399.R
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.core.tv.RemoteKeys
import blbl.cat3399.core.tv.TvMode
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.core.ui.UiScale
import blbl.cat3399.databinding.ActivityMainBinding
import blbl.cat3399.databinding.DialogUserInfoBinding
import blbl.cat3399.feature.category.CategoryFragment
import blbl.cat3399.feature.dynamic.DynamicFragment
import blbl.cat3399.feature.following.FollowingListActivity
import blbl.cat3399.feature.home.HomeFragment
import blbl.cat3399.feature.live.LiveFragment
import blbl.cat3399.feature.login.QrLoginActivity
import blbl.cat3399.feature.my.MyFragment
import blbl.cat3399.feature.search.SearchFragment
import blbl.cat3399.feature.settings.SettingsActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var navAdapter: SidebarNavAdapter
    private var needForceInitialSidebarFocus: Boolean = false
    private var lastMainFocusedView: WeakReference<View>? = null
    private var pausedFocusedView: WeakReference<View>? = null
    private var pausedFocusWasInMain: Boolean = false
    private var focusListener: ViewTreeObserver.OnGlobalFocusChangeListener? = null
    private var disclaimerDialog: AlertDialog? = null
    private lateinit var userInfoOverlay: DialogUserInfoBinding
    private var userInfoPrevFocus: WeakReference<View>? = null
    private var userInfoLoadJob: Job? = null
    private var lastBackAtMs: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        needForceInitialSidebarFocus = savedInstanceState == null
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
        applyUiMode()

        userInfoOverlay = binding.userInfoOverlay
        initUserInfoOverlay()

        binding.btnSidebarLogin.setOnClickListener { openQrLogin() }
        binding.ivSidebarUser.setOnClickListener {
            if (!BiliClient.cookies.hasSessData()) {
                openQrLogin()
                return@setOnClickListener
            }
            showUserInfoOverlay()
        }
        binding.btnSidebarSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        navAdapter = SidebarNavAdapter(
            onClick = { item ->
                AppLog.d("Nav", "sidebar click id=${item.id} title=${item.title} t=${SystemClock.uptimeMillis()}")
                when (item.id) {
                    SidebarNavAdapter.ID_SEARCH -> showRoot(SearchFragment.newInstance())
                    SidebarNavAdapter.ID_HOME -> showRoot(HomeFragment.newInstance())
                    SidebarNavAdapter.ID_CATEGORY -> showRoot(CategoryFragment.newInstance())
                    SidebarNavAdapter.ID_DYNAMIC -> showRoot(DynamicFragment.newInstance())
                    SidebarNavAdapter.ID_LIVE -> showRoot(LiveFragment.newInstance())
                    SidebarNavAdapter.ID_MY -> showRoot(MyFragment.newInstance())
                    else -> false
                }
            },
        )
        binding.recyclerSidebar.layoutManager = LinearLayoutManager(this)
        binding.recyclerSidebar.adapter = navAdapter
        (binding.recyclerSidebar.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        navAdapter.submit(
            listOf(
                SidebarNavAdapter.NavItem(SidebarNavAdapter.ID_SEARCH, getString(R.string.tab_search), R.drawable.ic_nav_search),
                SidebarNavAdapter.NavItem(SidebarNavAdapter.ID_HOME, getString(R.string.tab_recommend), R.drawable.ic_nav_home),
                SidebarNavAdapter.NavItem(SidebarNavAdapter.ID_CATEGORY, getString(R.string.tab_category), R.drawable.ic_nav_category),
                SidebarNavAdapter.NavItem(SidebarNavAdapter.ID_DYNAMIC, getString(R.string.tab_dynamic), R.drawable.ic_nav_dynamic),
                SidebarNavAdapter.NavItem(SidebarNavAdapter.ID_LIVE, getString(R.string.tab_live), R.drawable.ic_nav_live),
                SidebarNavAdapter.NavItem(SidebarNavAdapter.ID_MY, getString(R.string.tab_my), R.drawable.ic_nav_my),
            ),
            selectedId = SidebarNavAdapter.ID_HOME,
        )

        if (savedInstanceState == null) {
            navAdapter.select(SidebarNavAdapter.ID_HOME, trigger = true)
        }

        focusListener =
            ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
                if (newFocus != null && isInMainContainer(newFocus)) {
                    lastMainFocusedView = WeakReference(newFocus)
                }
            }.also { binding.root.viewTreeObserver.addOnGlobalFocusChangeListener(it) }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (isUserInfoOverlayVisible()) {
                        hideUserInfoOverlay()
                        return
                    }
                    if (supportFragmentManager.popBackStackImmediate()) {
                        return
                    }
                    val current = supportFragmentManager.findFragmentById(R.id.main_container)
                    val handled = (current as? BackPressHandler)?.handleBackPressed() == true
                    AppLog.d("Back", "back current=${current?.javaClass?.simpleName} handled=$handled")
                    if (handled) return
                    if (current !is HomeFragment) {
                        navAdapter.select(SidebarNavAdapter.ID_HOME, trigger = true)
                        return
                    }
                    if (shouldFinishOnBackPress()) finish()
                }
            },
        )

        refreshSidebarUser()
        showFirstLaunchDisclaimerIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        applyUiMode()
        restoreFocusAfterResume()
        forceInitialSidebarFocusIfNeeded()
        ensureInitialFocus()
        refreshSidebarUser()
    }

    override fun onPause() {
        val focused = currentFocus
        pausedFocusedView = focused?.let { WeakReference(it) }
        pausedFocusWasInMain = focused != null && isInMainContainer(focused)
        super.onPause()
    }

    override fun onDestroy() {
        focusListener?.let { binding.root.viewTreeObserver.removeOnGlobalFocusChangeListener(it) }
        focusListener = null
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isUserInfoOverlayVisible()) {
            return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0 && RemoteKeys.isRefreshKey(event.keyCode)) {
                if (dispatchRefreshKeyToCurrentPage()) return true
            }

            val focused = currentFocus
            if (focused == null && isNavKey(event.keyCode)) {
                ensureInitialFocus()
                return true
            }

            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (focused != null && isInMainContainer(focused)) {
                        if (tryMoveDynamicVideoToFollowing(focused)) return true
                        // If the current view can move LEFT within the main container (e.g. SearchFragment
                        // history/hot lists), don't steal the key event to enter the sidebar.
                        val next = focused.focusSearch(View.FOCUS_LEFT)
                        if (next != null && isInMainContainer(next)) {
                            return super.dispatchKeyEvent(event)
                        }
                        if (canEnterSidebarFrom(focused)) {
                            focusSidebarFirstNav()
                            return true
                        }
                    }
                }

                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (focused != null && isInSidebar(focused)) {
                        if (focused == binding.ivSidebarUser || focused == binding.btnSidebarLogin) {
                            if (focusSelectedTabInCurrentFragment()) return true
                        }
                        focusMainFromSidebar()
                        return true
                    }
                }

                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                -> {
                    if (focused != null && isInSidebar(focused)) {
                        val moved = moveSidebarFocus(up = event.keyCode == KeyEvent.KEYCODE_DPAD_UP)
                        if (moved) return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun dispatchRefreshKeyToCurrentPage(): Boolean {
        val root = supportFragmentManager.findFragmentById(R.id.main_container) ?: return false
        val handler = findRefreshKeyHandler(root) ?: return false
        return handler.handleRefreshKey()
    }

    private fun findRefreshKeyHandler(fragment: Fragment): RefreshKeyHandler? {
        return findRefreshKeyHandler(fragment, preferResumed = true) ?: findRefreshKeyHandler(fragment, preferResumed = false)
    }

    private fun findRefreshKeyHandler(fragment: Fragment, preferResumed: Boolean): RefreshKeyHandler? {
        if (!fragment.isAdded) return null

        fragment.childFragmentManager.fragments.asReversed().forEach { child ->
            val found = findRefreshKeyHandler(child, preferResumed)
            if (found != null) return found
        }

        val active = if (preferResumed) fragment.isResumed else fragment.isVisible
        return if (active && fragment is RefreshKeyHandler) fragment else null
    }

    private fun initUserInfoOverlay() {
        userInfoOverlay.root.visibility = View.GONE

        userInfoOverlay.root.setOnClickListener { hideUserInfoOverlay() }
        userInfoOverlay.card.setOnClickListener { /* consume */ }
        userInfoOverlay.btnFollowing.setOnClickListener {
            hideUserInfoOverlay()
            startActivity(Intent(this, FollowingListActivity::class.java))
        }
        userInfoOverlay.btnFollower.setOnClickListener {
            Toast.makeText(this, "粉丝列表未实现", Toast.LENGTH_SHORT).show()
        }
        userInfoOverlay.btnLogout.setOnClickListener { showLogoutConfirm() }

        val invalidateOverlay = View.OnFocusChangeListener { _, _ ->
            userInfoOverlay.card.invalidate()
            userInfoOverlay.root.invalidate()
        }
        userInfoOverlay.btnFollowing.onFocusChangeListener = invalidateOverlay
        userInfoOverlay.btnFollower.onFocusChangeListener = invalidateOverlay
        userInfoOverlay.btnLogout.onFocusChangeListener = invalidateOverlay
    }

    private fun isUserInfoOverlayVisible(): Boolean = userInfoOverlay.root.visibility == View.VISIBLE

    private fun showUserInfoOverlay() {
        if (isUserInfoOverlayVisible()) return
        userInfoPrevFocus = currentFocus?.let { WeakReference(it) }

        binding.sidebar.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        binding.mainContainer.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS

        userInfoOverlay.root.visibility = View.VISIBLE
        resetUserInfoUi()
        loadUserInfo()

        userInfoOverlay.root.post {
            userInfoOverlay.btnFollowing.requestFocus()
        }
    }

    private fun hideUserInfoOverlay() {
        if (!isUserInfoOverlayVisible()) return
        userInfoLoadJob?.cancel()
        userInfoLoadJob = null
        userInfoOverlay.root.visibility = View.GONE

        binding.sidebar.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        binding.mainContainer.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        val desired = userInfoPrevFocus?.get()
        userInfoPrevFocus = null
        if (desired != null && desired.isAttachedToWindow && desired.isShown) {
            binding.root.post { desired.requestFocus() }
        }
    }

    private fun resetUserInfoUi() {
        userInfoOverlay.tvName.text = ""
        userInfoOverlay.tvMid.text = ""
        userInfoOverlay.tvFollowing.text = "--"
        userInfoOverlay.tvFollower.text = "--"
        userInfoOverlay.tvCoins.text = "--"
        userInfoOverlay.tvLevel.text = ""
        userInfoOverlay.tvExp.text = ""
        userInfoOverlay.progressExp.visibility = View.GONE
        userInfoOverlay.pbLoading.visibility = View.GONE
    }

    private fun loadUserInfo() {
        userInfoLoadJob?.cancel()
        userInfoOverlay.pbLoading.visibility = View.VISIBLE
        userInfoLoadJob =
            lifecycleScope.launch {
                runCatching {
                    val nav = BiliApi.nav()
                    val data = nav.optJSONObject("data")
                    val isLogin = data?.optBoolean("isLogin") ?: false
                    if (!isLogin) {
                        hideUserInfoOverlay()
                        return@launch
                    }

                    val mid = data?.optLong("mid") ?: 0L
                    val name = data?.optString("uname", "").orEmpty()
                    val avatarUrl = data?.optString("face")?.takeIf { it.isNotBlank() }

                    val coins = parseCoins(data)
                    val levelInfo = data?.optJSONObject("level_info")
                    val level = levelInfo?.optInt("current_level") ?: 0
                    val currentExp = parseInt(levelInfo, "current_exp") ?: 0
                    val nextExp = parseInt(levelInfo, "next_exp")

                    val stat = if (mid > 0) BiliApi.relationStat(mid) else null

                    userInfoOverlay.tvName.text = name
                    userInfoOverlay.tvMid.text = getString(R.string.label_uid_fmt, mid.toString())
                    val normalizedUrl = blbl.cat3399.core.image.ImageUrl.avatar(avatarUrl)
                    blbl.cat3399.core.image.ImageLoader.loadInto(userInfoOverlay.ivAvatar, normalizedUrl)

                    userInfoOverlay.tvFollowing.text = (stat?.following ?: 0L).toString()
                    userInfoOverlay.tvFollower.text = (stat?.follower ?: 0L).toString()
                    userInfoOverlay.tvCoins.text = formatCoins(coins)

                    userInfoOverlay.tvLevel.text = getString(R.string.label_level_fmt, level)
                    val expText = if (nextExp != null && nextExp > 0) "$currentExp/$nextExp" else "已满级"
                    userInfoOverlay.tvExp.text = getString(R.string.label_exp_fmt, expText)

                    if (nextExp != null && nextExp > 0) {
                        userInfoOverlay.progressExp.visibility = View.VISIBLE
                        userInfoOverlay.progressExp.max = nextExp
                        userInfoOverlay.progressExp.progress = currentExp.coerceIn(0, nextExp)
                    } else {
                        userInfoOverlay.progressExp.visibility = View.GONE
                    }
                }.onFailure {
                    AppLog.w("MainActivity", "loadUserInfo failed", it)
                    if (!isUserInfoOverlayVisible()) return@launch
                    userInfoOverlay.tvName.text = "加载失败"
                    userInfoOverlay.tvMid.text = it.message.orEmpty()
                }
                userInfoOverlay.pbLoading.visibility = View.GONE
            }
    }

    private fun showLogoutConfirm() {
        MaterialAlertDialogBuilder(this)
            .setTitle("退出登录")
            .setMessage("将清除 Cookie（SESSDATA 等），需要重新登录。确定继续吗？")
            .setPositiveButton("确定退出") { _, _ ->
                BiliClient.cookies.clearAll()
                BiliClient.prefs.webRefreshToken = null
                BiliClient.prefs.webCookieRefreshCheckedEpochDay = -1L
                BiliClient.prefs.biliTicketCheckedEpochDay = -1L
                Toast.makeText(this, "已退出登录", Toast.LENGTH_SHORT).show()
                hideUserInfoOverlay()
                refreshSidebarUser()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun parseCoins(data: JSONObject?): Double {
        if (data == null) return 0.0
        val money = data.optDouble("money", Double.NaN)
        if (!money.isNaN()) return money
        val coins = data.optDouble("coins", Double.NaN)
        if (!coins.isNaN()) return coins
        return 0.0
    }

    private fun formatCoins(value: Double): String {
        val v = value.coerceAtLeast(0.0)
        return if (v >= 1000) String.format(Locale.getDefault(), "%.0f", v) else String.format(Locale.getDefault(), "%.1f", v)
    }

    private fun parseInt(obj: JSONObject?, key: String): Int? {
        val any = obj?.opt(key) ?: return null
        return when (any) {
            is Number -> any.toInt()
            is String -> any.toIntOrNull()
            else -> null
        }
    }

    private fun showRoot(fragment: androidx.fragment.app.Fragment): Boolean {
        AppLog.d("MainActivity", "showRoot ${fragment.javaClass.simpleName} t=${SystemClock.uptimeMillis()}")
        runCatching { supportFragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE) }
        supportFragmentManager.beginTransaction()
            .replace(R.id.main_container, fragment)
            .commitAllowingStateLoss()
        return true
    }

    private fun openQrLogin() {
        AppLog.i("MainActivity", "openQrLogin")
        startActivity(Intent(this, QrLoginActivity::class.java))
    }

    private fun shouldFinishOnBackPress(): Boolean {
        val now = SystemClock.uptimeMillis()
        val isSecond = now - lastBackAtMs <= BACK_DOUBLE_PRESS_WINDOW_MS
        if (isSecond) return true
        lastBackAtMs = now
        Toast.makeText(this, "再按一次退出应用", Toast.LENGTH_SHORT).show()
        return false
    }

    private fun showFirstLaunchDisclaimerIfNeeded() {
        if (BiliClient.prefs.disclaimerAccepted) return
        if (disclaimerDialog?.isShowing == true) return

        val dialog =
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.disclaimer_title))
                .setMessage(getString(R.string.disclaimer_message))
                .setCancelable(false)
                .setNegativeButton(getString(R.string.disclaimer_exit)) { _, _ -> finish() }
                .setPositiveButton(getString(R.string.disclaimer_accept)) { _, _ ->
                    BiliClient.prefs.disclaimerAccepted = true
                }
                .create()
        dialog.setOnDismissListener {
            disclaimerDialog = null
            if (!BiliClient.prefs.disclaimerAccepted && !isChangingConfigurations) finish()
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
        }
        dialog.show()
        disclaimerDialog = dialog
    }

    private fun refreshSidebarUser() {
        val hasCookie = BiliClient.cookies.hasSessData()
        if (!hasCookie) {
            showLoggedOut()
            return
        }
        lifecycleScope.launch {
            runCatching {
                val nav = BiliApi.nav()
                val data = nav.optJSONObject("data")
                val isLogin = data?.optBoolean("isLogin") ?: false
                val avatarUrl = data?.optString("face")?.takeIf { it.isNotBlank() }
                if (isLogin) showLoggedIn(avatarUrl) else showLoggedOut()
            }.onFailure {
                AppLog.w("MainActivity", "refreshSidebarUser failed", it)
            }
        }
    }

    private fun showLoggedIn(avatarUrl: String?) {
        binding.btnSidebarLogin.visibility = android.view.View.GONE
        binding.ivSidebarUser.visibility = android.view.View.VISIBLE
        val normalizedUrl = blbl.cat3399.core.image.ImageUrl.avatar(avatarUrl)
        blbl.cat3399.core.image.ImageLoader.loadInto(binding.ivSidebarUser, normalizedUrl)
        if (binding.btnSidebarLogin.isFocused) {
            binding.ivSidebarUser.requestFocus()
        }
    }

    private fun showLoggedOut() {
        binding.ivSidebarUser.visibility = android.view.View.GONE
        binding.btnSidebarLogin.visibility = android.view.View.VISIBLE
        if (binding.ivSidebarUser.isFocused) {
            binding.btnSidebarLogin.requestFocus()
        }
    }

    private fun applyUiMode() {
        val tvMode = TvMode.isEnabled(this)
        val sizePref = BiliClient.prefs.sidebarSize
        val sidebarScale = UiScale.factor(this, tvMode, sizePref)
        val sidebarWidthScale = UiScale.densityFixFactor(this, tvMode)
        if (::navAdapter.isInitialized) {
            navAdapter.setTvMode(tvMode)
            navAdapter.setSidebarScale(sidebarScale)
        }

        val widthPx =
            (resources.getDimensionPixelSize(
                sidebarWidthDimenFor(tvMode, sizePref),
            ) * sidebarWidthScale).roundToInt().coerceAtLeast(1)
        val lp = binding.sidebar.layoutParams
        if (lp.width != widthPx) {
            lp.width = widthPx
            binding.sidebar.layoutParams = lp
        }

        applySidebarSizing(tvMode, sidebarScale)
    }

    private fun applySidebarSizing(tvMode: Boolean, sidebarScale: Float) {
        fun px(id: Int): Int = resources.getDimensionPixelSize(id)
        fun pxF(id: Int): Float = resources.getDimension(id)
        val scale = sidebarScale.coerceIn(0.60f, 1.40f)
        fun scaledPx(id: Int): Int = (px(id) * scale).roundToInt()
        fun scaledPxF(id: Int): Float = pxF(id) * scale

        val userSize = scaledPx(if (tvMode) R.dimen.sidebar_user_size_tv else R.dimen.sidebar_user_size)
        setSize(binding.ivSidebarUser, userSize, userSize)
        setTopMargin(binding.ivSidebarUser, scaledPx(if (tvMode) R.dimen.sidebar_user_margin_top_tv else R.dimen.sidebar_user_margin_top))

        val loginSize = scaledPx(if (tvMode) R.dimen.sidebar_login_size_tv else R.dimen.sidebar_login_size)
        setSize(binding.btnSidebarLogin, loginSize, loginSize)
        setTopMargin(binding.btnSidebarLogin, scaledPx(if (tvMode) R.dimen.sidebar_login_margin_top_tv else R.dimen.sidebar_login_margin_top))
        binding.btnSidebarLogin.setTextSize(
            android.util.TypedValue.COMPLEX_UNIT_PX,
            scaledPxF(if (tvMode) R.dimen.sidebar_login_text_size_tv else R.dimen.sidebar_login_text_size),
        )

        setTopMargin(binding.recyclerSidebar, scaledPx(if (tvMode) R.dimen.sidebar_nav_margin_top_tv else R.dimen.sidebar_nav_margin_top))

        val settingsSize = scaledPx(if (tvMode) R.dimen.sidebar_settings_size_tv else R.dimen.sidebar_settings_size)
        setSize(binding.btnSidebarSettings, settingsSize, settingsSize)
        setBottomMargin(
            binding.btnSidebarSettings,
            scaledPx(if (tvMode) R.dimen.sidebar_settings_margin_bottom_tv else R.dimen.sidebar_settings_margin_bottom),
        )
        val settingsPadding = scaledPx(if (tvMode) R.dimen.sidebar_settings_padding_tv else R.dimen.sidebar_settings_padding)
        binding.btnSidebarSettings.setPadding(settingsPadding, settingsPadding, settingsPadding, settingsPadding)
    }

    private fun setSize(view: View, widthPx: Int, heightPx: Int) {
        val lp = view.layoutParams ?: return
        if (lp.width == widthPx && lp.height == heightPx) return
        lp.width = widthPx
        lp.height = heightPx
        view.layoutParams = lp
    }

    private fun setTopMargin(view: View, topMarginPx: Int) {
        val lp = view.layoutParams
        when (lp) {
            is LinearLayout.LayoutParams -> {
                if (lp.topMargin == topMarginPx) return
                lp.topMargin = topMarginPx
                view.layoutParams = lp
            }

            is MarginLayoutParams -> {
                if (lp.topMargin == topMarginPx) return
                lp.topMargin = topMarginPx
                view.layoutParams = lp
            }
        }
    }

    private fun setBottomMargin(view: View, bottomMarginPx: Int) {
        val lp = view.layoutParams
        when (lp) {
            is LinearLayout.LayoutParams -> {
                if (lp.bottomMargin == bottomMarginPx) return
                lp.bottomMargin = bottomMarginPx
                view.layoutParams = lp
            }

            is MarginLayoutParams -> {
                if (lp.bottomMargin == bottomMarginPx) return
                lp.bottomMargin = bottomMarginPx
                view.layoutParams = lp
            }
        }
    }

    private fun ensureInitialFocus() {
        if (currentFocus != null) return
        val pos = navAdapter.selectedAdapterPosition().takeIf { it >= 0 } ?: 0
        binding.recyclerSidebar.post {
            val vh = binding.recyclerSidebar.findViewHolderForAdapterPosition(pos)
            if (vh != null) {
                vh.itemView.requestFocus()
                return@post
            }
            binding.recyclerSidebar.scrollToPosition(pos)
            binding.recyclerSidebar.post {
                binding.recyclerSidebar.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
            }
        }
    }

    private fun forceInitialSidebarFocusIfNeeded() {
        if (!needForceInitialSidebarFocus) return
        val focused = currentFocus
        if (focused == null || !isDescendantOf(focused, binding.recyclerSidebar)) {
            focusSidebarFirstNav()
        }
        needForceInitialSidebarFocus = false
    }

    private fun restoreFocusAfterResume() {
        val desired = pausedFocusedView?.get()
        if (desired != null && desired.isAttachedToWindow && desired.isShown) {
            binding.root.post { desired.requestFocus() }
            return
        }
        if (!pausedFocusWasInMain) return

        val lastMain = lastMainFocusedView?.get()
        if (lastMain != null && lastMain.isAttachedToWindow && lastMain.isShown && isInMainContainer(lastMain)) {
            binding.root.post { lastMain.requestFocus() }
            return
        }

        val focusedNow = currentFocus
        if (focusedNow != null && isInSidebar(focusedNow)) {
            binding.root.post { focusMainFromSidebar() }
        }
    }

    private fun isInSidebar(view: View): Boolean = isDescendantOf(view, binding.sidebar)

    private fun sidebarWidthDimenFor(tvMode: Boolean, prefValue: String): Int {
        if (tvMode) {
            return when (prefValue) {
                AppPrefs.SIDEBAR_SIZE_SMALL -> R.dimen.sidebar_width_tv_small
                AppPrefs.SIDEBAR_SIZE_LARGE -> R.dimen.sidebar_width_tv_large
                else -> R.dimen.sidebar_width_tv
            }
        }
        return when (prefValue) {
            AppPrefs.SIDEBAR_SIZE_SMALL -> R.dimen.sidebar_width_small
            AppPrefs.SIDEBAR_SIZE_LARGE -> R.dimen.sidebar_width_large
            else -> R.dimen.sidebar_width_normal
        }
    }

    private fun isInMainContainer(view: View): Boolean = isDescendantOf(view, binding.mainContainer)

    private fun isDescendantOf(view: View, ancestor: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current == ancestor) return true
            val parent = current.parent
            current = parent as? View
        }
        return false
    }

    private fun focusSidebarFirstNav(): Boolean = focusSidebarNavAt(0)

    private fun focusSidebarNavAt(position: Int): Boolean {
        if (position < 0 || position >= navAdapter.itemCount) return false
        binding.recyclerSidebar.post {
            val vh = binding.recyclerSidebar.findViewHolderForAdapterPosition(position)
            if (vh != null) {
                vh.itemView.requestFocus()
                return@post
            }
            binding.recyclerSidebar.scrollToPosition(position)
            binding.recyclerSidebar.post {
                binding.recyclerSidebar.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
            }
        }
        return true
    }

    private fun focusMainFromSidebar(): Boolean {
        val fragmentView = supportFragmentManager.findFragmentById(R.id.main_container)?.view ?: return false

        val recyclerFollowing = fragmentView.findViewById<RecyclerView?>(R.id.recycler_following)
        if (recyclerFollowing != null) {
            val lastMain = lastMainFocusedView?.get()
            if (lastMain != null &&
                lastMain.isAttachedToWindow &&
                lastMain.isShown &&
                isDescendantOf(lastMain, recyclerFollowing)
            ) {
                lastMain.requestFocus()
                return true
            }

            recyclerFollowing.post {
                val vh = recyclerFollowing.findViewHolderForAdapterPosition(0)
                if (vh != null) {
                    vh.itemView.requestFocus()
                    return@post
                }
                if (recyclerFollowing.adapter?.itemCount == 0) {
                    recyclerFollowing.requestFocus()
                    return@post
                }
                recyclerFollowing.scrollToPosition(0)
                recyclerFollowing.post {
                    recyclerFollowing.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() ?: recyclerFollowing.requestFocus()
                }
            }
            return true
        }

        val lastMain = lastMainFocusedView?.get()
        if (lastMain != null && lastMain.isAttachedToWindow && lastMain.isShown && isInMainContainer(lastMain)) {
            lastMain.requestFocus()
            return true
        }

        val recycler =
            fragmentView.findViewById<RecyclerView?>(R.id.recycler_dynamic)
                ?: fragmentView.findViewById<RecyclerView?>(R.id.recycler)

        if (recycler != null) {
            recycler.post {
                val vh = recycler.findViewHolderForAdapterPosition(0)
                if (vh != null) {
                    vh.itemView.requestFocus()
                    return@post
                }
                recycler.scrollToPosition(0)
                recycler.post {
                    recycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() ?: recycler.requestFocus()
                }
            }
            return true
        }

        val tabLayout = fragmentView.findViewById<com.google.android.material.tabs.TabLayout?>(R.id.tab_layout)
        if (tabLayout != null) {
            val tabStrip = tabLayout.getChildAt(0) as? ViewGroup
            val pos = tabLayout.selectedTabPosition.takeIf { it >= 0 } ?: 0
            tabStrip?.getChildAt(pos)?.requestFocus()
            return true
        }

        val dynamicLoginBtn = fragmentView.findViewById<View?>(R.id.btn_login)
        if (dynamicLoginBtn != null && dynamicLoginBtn.isShown && dynamicLoginBtn.isFocusable) {
            dynamicLoginBtn.requestFocus()
            return true
        }

        fragmentView.requestFocus()
        return true
    }

    private fun focusSelectedTabInCurrentFragment(): Boolean {
        val fragmentView = supportFragmentManager.findFragmentById(R.id.main_container)?.view ?: return false
        val tabLayout = fragmentView.findViewById<com.google.android.material.tabs.TabLayout?>(R.id.tab_layout) ?: return false
        val tabStrip = tabLayout.getChildAt(0) as? ViewGroup ?: return false
        val pos = tabLayout.selectedTabPosition.takeIf { it >= 0 } ?: 0
        tabLayout.post { tabStrip.getChildAt(pos)?.requestFocus() }
        return true
    }

    private fun tryMoveDynamicVideoToFollowing(focused: View): Boolean {
        val fragmentView = supportFragmentManager.findFragmentById(R.id.main_container)?.view ?: return false
        val recyclerFollowing = fragmentView.findViewById<RecyclerView?>(R.id.recycler_following) ?: return false
        val recyclerDynamic = fragmentView.findViewById<RecyclerView?>(R.id.recycler_dynamic) ?: return false
        if (!isDescendantOf(focused, recyclerDynamic)) return false
        if (!isStaggeredGridLeftEdge(focused, recyclerDynamic)) return false

        recyclerFollowing.post {
            val selectedChild = (0 until recyclerFollowing.childCount)
                .map { recyclerFollowing.getChildAt(it) }
                .firstOrNull { it?.isSelected == true }
            if (selectedChild != null) {
                selectedChild.requestFocus()
                return@post
            }

            val vh = recyclerFollowing.findViewHolderForAdapterPosition(0)
            if (vh != null) {
                vh.itemView.requestFocus()
                return@post
            }
            if (recyclerFollowing.adapter?.itemCount == 0) {
                recyclerFollowing.requestFocus()
                return@post
            }
            recyclerFollowing.scrollToPosition(0)
            recyclerFollowing.post {
                recyclerFollowing.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() ?: recyclerFollowing.requestFocus()
            }
        }
        return true
    }

    private fun isStaggeredGridLeftEdge(view: View, recyclerView: RecyclerView): Boolean {
        recyclerView.layoutManager as? StaggeredGridLayoutManager ?: return false
        val itemView = recyclerView.findContainingItemView(view) ?: return false
        val lp = itemView.layoutParams as? StaggeredGridLayoutManager.LayoutParams ?: return false
        return lp.spanIndex == 0
    }

    private fun moveSidebarFocus(up: Boolean): Boolean {
        val focused = currentFocus ?: return false

        val movingToNav = !up && (focused == binding.ivSidebarUser || focused == binding.btnSidebarLogin)
        if (movingToNav) return focusSidebarFirstNav()

        if (focused == binding.btnSidebarSettings) {
            if (!up) return true
            return focusSidebarNavAt(navAdapter.itemCount - 1)
        }

        val inNav = isDescendantOf(focused, binding.recyclerSidebar)
        if (!inNav) {
            if (up) return true
            binding.btnSidebarSettings.requestFocus()
            return true
        }

        val holder = binding.recyclerSidebar.findContainingViewHolder(focused) ?: return false
        val pos = holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: return false

        return if (up) {
            if (pos > 0) {
                focusSidebarNavAt(pos - 1)
            } else {
                if (binding.ivSidebarUser.visibility == View.VISIBLE) {
                    binding.ivSidebarUser.requestFocus()
                } else if (binding.btnSidebarLogin.visibility == View.VISIBLE) {
                    binding.btnSidebarLogin.requestFocus()
                }
                true
            }
        } else {
            if (pos < navAdapter.itemCount - 1) {
                focusSidebarNavAt(pos + 1)
            } else {
                binding.btnSidebarSettings.requestFocus()
                true
            }
        }
    }

    private fun canEnterSidebarFrom(view: View): Boolean {
        val rv = view.findAncestorRecyclerView()
        if (rv != null) {
            val lm = rv.layoutManager
            if (lm is StaggeredGridLayoutManager) {
                val child = rv.findContainingItemView(view) ?: view
                val lp = child.layoutParams as? StaggeredGridLayoutManager.LayoutParams
                if (lp != null && lp.spanIndex == 0) {
                    val focusLoc = IntArray(2)
                    val containerLoc = IntArray(2)
                    child.getLocationOnScreen(focusLoc)
                    binding.mainContainer.getLocationOnScreen(containerLoc)
                    return (focusLoc[0] - containerLoc[0]) <= dp(24f)
                }
            }
        }

        val focusLoc = IntArray(2)
        val containerLoc = IntArray(2)
        view.getLocationOnScreen(focusLoc)
        binding.mainContainer.getLocationOnScreen(containerLoc)
        return (focusLoc[0] - containerLoc[0]) <= dp(24f)
    }

    private fun View.findAncestorRecyclerView(): RecyclerView? {
        var current: View? = this
        while (current != null) {
            if (current is RecyclerView) return current
            current = current.parent as? View
        }
        return null
    }

    private fun dp(valueDp: Float): Int {
        val dm = resources.displayMetrics
        return (valueDp * dm.density).toInt()
    }

    private fun isNavKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_TAB,
            -> true

            else -> false
        }
    }

    companion object {
        private const val BACK_DOUBLE_PRESS_WINDOW_MS = 1_500L
    }
}
