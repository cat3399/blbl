package blbl.cat3399.ui

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.view.ViewTreeObserver
import androidx.activity.OnBackPressedCallback
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.Lifecycle
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
import blbl.cat3399.core.log.CrashTracker
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.core.tv.RemoteKeys
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.BaseActivity
import blbl.cat3399.core.ui.FocusReturn
import blbl.cat3399.core.ui.FocusTreeUtils
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.core.ui.TabContentSwitchFocusHost
import blbl.cat3399.core.ui.cloneInUserScale
import blbl.cat3399.core.ui.dispatchToAncestorDpadItemKeyHandler
import blbl.cat3399.core.ui.popup.AppPopup
import blbl.cat3399.core.ui.popup.PopupHandle
import blbl.cat3399.databinding.ActivityMainBinding
import blbl.cat3399.databinding.DialogUserInfoBinding
import blbl.cat3399.feature.following.FollowingListActivity
import blbl.cat3399.feature.login.QrLoginActivity
import blbl.cat3399.feature.player.engine.IjkPlayerPlugin
import blbl.cat3399.feature.player.engine.IjkPlayerPluginUi
import blbl.cat3399.feature.settings.SettingsActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.Locale

class MainActivity : BaseActivity(), SidebarFocusHost {
    private lateinit var binding: ActivityMainBinding
    private lateinit var navAdapter: SidebarNavAdapter
    private var needForceInitialSidebarFocus: Boolean = false
    private var launchNavId: Int = SidebarNavAdapter.ID_HOME
    private var currentRootNavId: Int? = null
    private var lastMainFocusedView: WeakReference<View>? = null
    private var pausedFocusedView: WeakReference<View>? = null
    private var pausedFocusWasInMain: Boolean = false
    private var focusListener: ViewTreeObserver.OnGlobalFocusChangeListener? = null
    private var disclaimerPopup: PopupHandle? = null
    private var crashPromptPopup: PopupHandle? = null
    private var ijkKernelPromptPopup: PopupHandle? = null
    private var ijkKernelPromptShown: Boolean = false
    private lateinit var userInfoOverlay: DialogUserInfoBinding
    private val userInfoReturnFocus = FocusReturn()
    private var userInfoLoadJob: Job? = null
    private var isSidebarExpanded: Boolean = true
    private var pendingSidebarCollapseAfterMainFocus: Boolean = false
    private var pendingSidebarCollapseToken: Int = 0
    private var lastBackAtMs: Long = 0L
    private var lastMainFocusAtMs: Long = 0L

    private data class FocusabilitySnapshot(
        val descendantFocusability: Int,
        val isFocusable: Boolean,
        val isFocusableInTouchMode: Boolean,
    )

    private var dpadDownFocusGuardActive: Boolean = false
    private var dpadDownFocusGuardReleaseToken: Int = 0
    private val dpadDownFocusGuardSnapshots = java.util.WeakHashMap<ViewGroup, FocusabilitySnapshot>()

    private var baseUserInfoCardWidth: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val safeState = restoredState
        needForceInitialSidebarFocus = safeState == null
        binding = ActivityMainBinding.inflate(layoutInflater.cloneInUserScale(this))
        setContentView(binding.root)
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
        setSidebarExpanded(expanded = true)
        launchNavId = resolveLaunchNavId()

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

        val initialSelectedNavId =
            if (safeState == null) {
                launchNavId
            } else {
                safeState.getInt(STATE_KEY_ROOT_NAV_ID, -1).takeIf { isValidRootNavId(it) }
                    ?: inferCurrentRootNavIdFromFragments()
                    ?: launchNavId
            }

        navAdapter = SidebarNavAdapter(
            onClick = { item ->
                AppLog.d("Nav", "sidebar click id=${item.id} title=${item.title} t=${SystemClock.uptimeMillis()}")
                handleSidebarNavClick(item.id)
            },
        )
        binding.recyclerSidebar.layoutManager = LinearLayoutManager(this)
        binding.recyclerSidebar.adapter = navAdapter
        (binding.recyclerSidebar.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        navAdapter.submit(MainRootNavRegistry.sidebarItems(this), selectedId = initialSelectedNavId)

        if (safeState == null) {
            navAdapter.select(initialSelectedNavId, trigger = true)
        } else {
            restoreRootAfterRecreate(initialSelectedNavId)
        }

        focusListener =
            ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
                if (newFocus != null && isInMainContainer(newFocus)) {
                    lastMainFocusedView = WeakReference(newFocus)
                    lastMainFocusAtMs = SystemClock.uptimeMillis()
                    maybeCollapseSidebarAfterMainFocusTransfer(newFocus)
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
                    // Sidebar is the primary navigation layer on TV.
                    // When focus is already in sidebar, Back should enter the app-level exit flow
                    // (double press to exit) instead of jumping back to the startup page.
                    val focused = currentFocus
                    if (focused != null && isInSidebar(focused)) {
                        if (shouldFinishOnBackPress()) finish()
                        return
                    }
                    val current = currentRootFragment()
                    val handled = (current as? BackPressHandler)?.handleBackPressed() == true
                    AppLog.d("Back", "back current=${current?.javaClass?.simpleName} handled=$handled")
                    if (handled) return
                    if (!isAtLaunchRoot(current)) {
                        navAdapter.select(launchNavId, trigger = true)
                        return
                    }
                    if (shouldFinishOnBackPress()) finish()
                }
            },
        )

        refreshSidebarUser()
        showFirstLaunchDisclaimerIfNeeded()
    }

    private fun resolveLaunchNavId(): Int {
        return MainRootNavRegistry.resolveLaunchNavId(BiliClient.prefs.startupPage)
    }

    private fun isAtLaunchRoot(fragment: Fragment?): Boolean {
        return navIdForRootFragment(fragment) == launchNavId
    }

    override fun onResume() {
        super.onResume()
        syncSidebarNavState()
        syncSidebarExpansionWithPrefs()
        restoreFocusAfterResume()
        forceInitialSidebarFocusIfNeeded()
        ensureInitialFocus()
        refreshSidebarUser()
        showLastCrashPromptIfNeeded()
        showIjkKernelUpdatePromptIfNeeded()
    }

    override fun onPause() {
        val focused = currentFocus
        pausedFocusedView = focused?.let { WeakReference(it) }
        pausedFocusWasInMain = focused != null && isInMainContainer(focused)
        clearPendingSidebarCollapseAfterMainFocus()
        releaseDpadDownFocusGuard()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentRootNavId?.let { outState.putInt(STATE_KEY_ROOT_NAV_ID, it) }
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

    private fun shouldStartDpadDownFocusGuard(focused: View?): Boolean {
        if (isUserInfoOverlayVisible()) return false
        if (focused != null) {
            if (isInSidebar(focused)) return false
            return isInMainContainer(focused)
        }
        val now = SystemClock.uptimeMillis()
        return now - lastMainFocusAtMs <= 1_000L
    }

    private fun syncSidebarNavState() {
        launchNavId = resolveLaunchNavId()
        val currentValidNavId =
            (currentRootNavId?.takeIf { isValidRootNavId(it) }
                ?: inferCurrentRootNavIdFromFragments()?.takeIf { isValidRootNavId(it) })
        val desiredNavId =
            currentValidNavId
                ?: launchNavId.takeIf { isValidRootNavId(it) }
                ?: SidebarNavAdapter.ID_HOME

        val sidebarItems = MainRootNavRegistry.sidebarItems(this)
        if (!navAdapter.matches(sidebarItems, selectedId = desiredNavId)) {
            navAdapter.submit(sidebarItems, selectedId = desiredNavId)
        }

        if (currentValidNavId != null) {
            currentRootNavId = currentValidNavId
            return
        }
        switchRoot(desiredNavId, clearBackStack = true)
    }

    private fun syncSidebarExpansionWithPrefs() {
        if (BiliClient.prefs.mainAutoHideSidebarOnEnterContent) {
            if (currentFocus?.let(::isInSidebar) == true) {
                setSidebarExpanded(expanded = true)
            }
            return
        }
        setSidebarExpanded(expanded = true)
    }

    private fun snapshotAndBlockFocus(container: ViewGroup) {
        if (!dpadDownFocusGuardSnapshots.containsKey(container)) {
            dpadDownFocusGuardSnapshots[container] =
                FocusabilitySnapshot(
                    descendantFocusability = container.descendantFocusability,
                    isFocusable = container.isFocusable,
                    isFocusableInTouchMode = container.isFocusableInTouchMode,
                )
        }
        container.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        container.isFocusable = false
        container.isFocusableInTouchMode = false
    }

    private fun beginOrKeepDpadDownFocusGuard(focused: View?) {
        if (!shouldStartDpadDownFocusGuard(focused)) return
        dpadDownFocusGuardActive = true
        dpadDownFocusGuardReleaseToken++

        // Always block the left sidebar: it should only be entered explicitly (DPAD_LEFT),
        // never as a framework fallback when content is temporarily detached/rebound.
        snapshotAndBlockFocus(binding.sidebar)

        val rootView = currentRootFragment()?.view
        val tabLayout = rootView?.findViewById<ViewGroup?>(R.id.tab_layout)
        if (tabLayout != null && (focused == null || !FocusTreeUtils.isDescendantOf(focused, tabLayout))) {
            snapshotAndBlockFocus(tabLayout)
        }

        val recyclerFollowing = rootView?.findViewById<ViewGroup?>(R.id.recycler_following)
        if (recyclerFollowing != null && (focused == null || !FocusTreeUtils.isDescendantOf(focused, recyclerFollowing))) {
            snapshotAndBlockFocus(recyclerFollowing)
        }
    }

    private fun scheduleReleaseDpadDownFocusGuard() {
        if (!dpadDownFocusGuardActive) return
        val token = ++dpadDownFocusGuardReleaseToken
        binding.root.postDelayed(
            {
                if (!dpadDownFocusGuardActive) return@postDelayed
                if (dpadDownFocusGuardReleaseToken != token) return@postDelayed
                releaseDpadDownFocusGuard()
            },
            120L,
        )
    }

    private fun releaseDpadDownFocusGuard() {
        if (!dpadDownFocusGuardActive) return
        dpadDownFocusGuardActive = false
        val snapshots = dpadDownFocusGuardSnapshots.toMap()
        dpadDownFocusGuardSnapshots.clear()
        snapshots.forEach { (container, snap) ->
            container.descendantFocusability = snap.descendantFocusability
            container.isFocusable = snap.isFocusable
            container.isFocusableInTouchMode = snap.isFocusableInTouchMode
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isUserInfoOverlayVisible()) {
            return super.dispatchKeyEvent(event)
        }

        if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> beginOrKeepDpadDownFocusGuard(currentFocus)
                KeyEvent.ACTION_UP,
                -> scheduleReleaseDpadDownFocusGuard()
            }
        } else if (event.action == KeyEvent.ACTION_DOWN && dpadDownFocusGuardActive) {
            // The user pressed another key: stop guarding so normal navigation (e.g. DPAD_LEFT into
            // sidebar, DPAD_LEFT into Dynamic follow list) is not blocked.
            releaseDpadDownFocusGuard()
        }

        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0 && RemoteKeys.isRefreshKey(event.keyCode)) {
                if (dispatchRefreshKeyToCurrentPage()) return true
            }

            val focused = currentFocus
            if (focused == null && isNavKey(event.keyCode)) {
                // During adapter updates, focus can be temporarily lost. If we were in main very
                // recently, keep the event consumed (and keep focus-escape guards active) instead
                // of forcing focus back into sidebar.
                val now = SystemClock.uptimeMillis()
                if (now - lastMainFocusAtMs <= 1_000L) {
                    return true
                }
                if (event.repeatCount > 0) return true
                ensureInitialFocus()
                return true
            }

            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (focused != null && isInMainContainer(focused)) {
                        if (tryMoveDynamicVideoToFollowing(focused)) return true
                        if (focused.dispatchToAncestorDpadItemKeyHandler(event.keyCode, event)) {
                            return true
                        }
                        // If the current view can move LEFT within the main container (e.g. SearchFragment
                        // history/hot lists), don't steal the key event to enter the sidebar.
                        val next = focused.focusSearch(View.FOCUS_LEFT)
                        if (next != null && isInMainContainer(next)) {
                            return super.dispatchKeyEvent(event)
                        }
                        if (canEnterSidebarFrom(focused)) {
                            releaseDpadDownFocusGuard()
                            focusSidebarSelectedNav()
                            return true
                        }
                    }
                }

                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (focused != null && isInSidebar(focused)) {
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
        val root = currentRootFragment() ?: return false
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
            AppToast.show(this, "粉丝列表未实现")
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

    private fun clampUserInfoOverlayCardWidth() {
        val lp = userInfoOverlay.card.layoutParams as? MarginLayoutParams ?: return
        val baseWidth =
            baseUserInfoCardWidth
                ?: lp.width.takeIf { it > 0 }?.also { baseUserInfoCardWidth = it }
                ?: return

        val maxWidth = (resources.displayMetrics.widthPixels - lp.leftMargin - lp.rightMargin).coerceAtLeast(1)
        val clamped = baseWidth.coerceAtMost(maxWidth)
        if (lp.width != clamped) {
            lp.width = clamped
            userInfoOverlay.card.layoutParams = lp
        }
    }

    private fun showUserInfoOverlay() {
        if (isUserInfoOverlayVisible()) return
        setSidebarExpanded(expanded = true)
        userInfoReturnFocus.capture(currentFocus)
        clampUserInfoOverlayCardWidth()

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

        userInfoReturnFocus.restoreAndClear(postOnFail = true)
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
        AppPopup.confirm(
            context = this,
            title = "退出登录",
            message = "将清除 Cookie（SESSDATA 等），需要重新登录。确定继续吗？",
            positiveText = "确定退出",
            negativeText = "取消",
            onPositive = {
                BiliClient.clearLoginSession()
                AppToast.show(this, "已退出登录")
                hideUserInfoOverlay()
                refreshSidebarUser()
            },
        )
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

    private fun handleSidebarNavClick(navId: Int): Boolean {
        if (!isValidRootNavId(navId)) return false
        val current = currentRootNavId
        if (current != null && current == navId) {
            // Reselect: force refresh the current (visible) tab content.
            dispatchRefreshKeyToCurrentPage()
            return true
        }
        return switchRoot(navId, clearBackStack = true)
    }

    private fun restoreRootAfterRecreate(initialSelectedNavId: Int) {
        if (!isValidRootNavId(initialSelectedNavId)) return

        // If we have cached (tagged) roots, ensure only the selected one is visible/resumed.
        if (hasAnyTaggedRootFragments()) {
            switchRoot(initialSelectedNavId, clearBackStack = false)
            return
        }

        // Legacy restore path (before root caching existed): keep the restored fragment as-is.
        val current = supportFragmentManager.findFragmentById(R.id.main_container)
        currentRootNavId = navIdForRootFragment(current)
        if (currentRootNavId == null) {
            switchRoot(initialSelectedNavId, clearBackStack = false)
        }
    }

    private fun switchRoot(navId: Int, clearBackStack: Boolean): Boolean {
        if (!isValidRootNavId(navId)) return false

        AppLog.d("MainActivity", "switchRoot navId=$navId clearBackStack=$clearBackStack t=${SystemClock.uptimeMillis()}")
        val fm = supportFragmentManager
        if (clearBackStack) {
            runCatching { fm.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE) }
        }

        val targetTag = rootTagFor(navId)
        var target = fm.findFragmentByTag(targetTag)
        val tx = fm.beginTransaction().setReorderingAllowed(true)

        if (target == null) {
            target = createRootFragment(navId)
            tx.add(R.id.main_container, target, targetTag)
        }

        // Keep cached roots, but remove unrelated fragments when switching roots
        // (equivalent to the old replace() behavior).
        fm.fragments
            .filter { it.isAdded && it.id == R.id.main_container && it !== target }
            .forEach { other ->
                val isCachedRoot = MainRootNavRegistry.isRootTag(other.tag)
                if (!clearBackStack || isCachedRoot) {
                    tx.hide(other)
                    tx.setMaxLifecycle(other, Lifecycle.State.STARTED)
                } else {
                    tx.remove(other)
                }
            }

        if (target.isHidden) {
            tx.show(target)
        }
        tx.setMaxLifecycle(target, Lifecycle.State.RESUMED)
        tx.setPrimaryNavigationFragment(target)
        tx.commitAllowingStateLoss()

        currentRootNavId = navId
        return true
    }

    private fun currentRootFragment(): Fragment? {
        val fm = supportFragmentManager
        val id = currentRootNavId
        if (id != null) {
            fm.findFragmentByTag(rootTagFor(id))?.let { return it }
        }
        fm.primaryNavigationFragment?.let { return it }
        return fm.findFragmentById(R.id.main_container)
    }

    private fun inferCurrentRootNavIdFromFragments(): Int? {
        val fm = supportFragmentManager
        navIdForRootFragment(fm.primaryNavigationFragment)?.let { return it }

        MainRootNavRegistry.rootNavIds().forEach { navId ->
            val fragment = fm.findFragmentByTag(rootTagFor(navId)) ?: return@forEach
            if (fragment.isAdded && !fragment.isHidden) return navId
        }

        return navIdForRootFragment(fm.findFragmentById(R.id.main_container))
    }

    private fun hasAnyTaggedRootFragments(): Boolean {
        val fm = supportFragmentManager
        return MainRootNavRegistry.rootNavIds().any { fm.findFragmentByTag(rootTagFor(it)) != null }
    }

    private fun createRootFragment(navId: Int): Fragment {
        val spec = MainRootNavRegistry.enabledSpecForNavId(navId)
            ?: throw IllegalArgumentException("Unknown root navId=$navId")
        return spec.fragmentFactory()
    }

    private fun rootTagFor(navId: Int): String {
        return MainRootNavRegistry.rootTagFor(navId)
    }

    private fun navIdForRootFragment(fragment: Fragment?): Int? {
        val navId = MainRootNavRegistry.navIdForFragment(fragment) ?: return null
        return navId.takeIf { isValidRootNavId(it) }
    }

    private fun isValidRootNavId(id: Int): Boolean {
        return MainRootNavRegistry.enabledSpecForNavId(id) != null
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
        AppToast.show(this, "再按一次退出应用")
        return false
    }

    private fun showFirstLaunchDisclaimerIfNeeded() {
        if (BiliClient.prefs.disclaimerAccepted) return
        if (disclaimerPopup?.isShowing == true) return

        disclaimerPopup =
            AppPopup.confirm(
                context = this,
                title = getString(R.string.disclaimer_title),
                message = getString(R.string.disclaimer_message),
                positiveText = getString(R.string.disclaimer_accept),
                negativeText = getString(R.string.disclaimer_exit),
                cancelable = false,
                onPositive = { BiliClient.prefs.disclaimerAccepted = true },
                onNegative = { finish() },
                onDismiss = {
                    disclaimerPopup = null
                    if (!BiliClient.prefs.disclaimerAccepted && !isChangingConfigurations) finish()
                    showIjkKernelUpdatePromptIfNeeded()
                },
            )
    }

    private fun showLastCrashPromptIfNeeded() {
        if (!BiliClient.prefs.disclaimerAccepted) return
        if (disclaimerPopup?.isShowing == true) return
        if (crashPromptPopup?.isShowing == true) return

        val crash = CrashTracker.loadLastCrash(this) ?: return
        if (CrashTracker.wasPrompted(this, crash.crashAtMs)) return

        CrashTracker.markPrompted(this, crash.crashAtMs)
        crashPromptPopup =
            AppPopup.confirm(
                context = this,
                title = "检测到上次异常退出",
                message = "为了帮助开发者定位问题，请到「设置 - 关于应用」导出或上传日志。",
                positiveText = "打开设置",
                negativeText = "知道了",
                onPositive = { startActivity(Intent(this, SettingsActivity::class.java)) },
                onDismiss = {
                    crashPromptPopup = null
                    showIjkKernelUpdatePromptIfNeeded()
                },
            )
    }

    private fun showIjkKernelUpdatePromptIfNeeded() {
        if (ijkKernelPromptShown) return
        if (!BiliClient.prefs.disclaimerAccepted) return
        if (BiliClient.prefs.playerEngineKind != AppPrefs.PLAYER_ENGINE_IJK) return
        if (disclaimerPopup?.isShowing == true) return
        if (crashPromptPopup?.isShowing == true) return
        if (ijkKernelPromptPopup?.isShowing == true) return

        val status = IjkPlayerPlugin.status(this)
        val (title, message) =
            when (status) {
                IjkPlayerPlugin.InstallStatus.NeedsUpdate ->
                    "播放器内核需要更新" to "当前默认播放器内核是 IjkPlayer，本地内核不是当前应用要求的最新版。更新前不会加载旧 IjkPlayer 内核。"

                IjkPlayerPlugin.InstallStatus.NotInstalled ->
                    "播放器内核未安装" to "当前默认播放器内核是 IjkPlayer，需要先下载播放器内核。"

                else -> return
            }

        ijkKernelPromptShown = true
        ijkKernelPromptPopup =
            AppPopup.confirm(
                context = this,
                title = title,
                message = message,
                positiveText = "立即更新",
                negativeText = "稍后",
                onPositive = {
                    IjkPlayerPluginUi.ensureInstalled(this) {}
                },
                onDismiss = {
                    ijkKernelPromptPopup = null
                },
            )
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

    private fun ensureInitialFocus() {
        if (currentFocus != null) return
        val pos = navAdapter.selectedAdapterPosition().takeIf { it >= 0 } ?: 0
        binding.recyclerSidebar.post {
            if (currentFocus != null) return@post
            val vh = binding.recyclerSidebar.findViewHolderForAdapterPosition(pos)
            if (vh != null) {
                vh.itemView.requestFocus()
                return@post
            }
            binding.recyclerSidebar.scrollToPosition(pos)
            binding.recyclerSidebar.post {
                if (currentFocus == null) {
                    binding.recyclerSidebar.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
                }
            }
        }
    }

    private fun forceInitialSidebarFocusIfNeeded() {
        if (!needForceInitialSidebarFocus) return
        val focused = currentFocus
        if (focused == null || !FocusTreeUtils.isDescendantOf(focused, binding.recyclerSidebar)) {
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
            // Avoid stealing focus back into main if a child fragment has already restored focus
            // (e.g. returning from playback and detail page restores a specific card).
            binding.root.post {
                val cur = currentFocus
                if (cur != null && isInSidebar(cur)) {
                    focusMainFromSidebar()
                }
            }
        }
    }

    private fun isInSidebar(view: View): Boolean = FocusTreeUtils.isDescendantOf(view, binding.sidebar)

    private fun isInMainContainer(view: View): Boolean = FocusTreeUtils.isDescendantOf(view, binding.mainContainer)

    private fun focusSidebarFirstNav(): Boolean = focusSidebarNavAt(0)

    private fun focusSidebarSelectedNav(): Boolean {
        val pos = navAdapter.selectedAdapterPosition().takeIf { it >= 0 } ?: 0
        return focusSidebarNavAt(pos)
    }

    override fun requestFocusSidebarSelectedNav(): Boolean {
        return focusSidebarSelectedNav()
    }

    private fun focusSidebarNavAt(position: Int): Boolean {
        if (position < 0 || position >= navAdapter.itemCount) return false
        clearPendingSidebarCollapseAfterMainFocus()
        setSidebarExpanded(expanded = true)
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
        val rootFragment = currentRootFragment() ?: return false
        val fragmentView = rootFragment.view ?: return false
        val armSidebarCollapse = BiliClient.prefs.mainAutoHideSidebarOnEnterContent

        fun armSidebarCollapseAfterFocusTransfer() {
            if (armSidebarCollapse) {
                prepareSidebarCollapseAfterMainFocus()
            } else {
                clearPendingSidebarCollapseAfterMainFocus()
            }
        }

        val recyclerFollowing = fragmentView.findViewById<RecyclerView?>(R.id.recycler_following)
        if (recyclerFollowing != null) {
            val lastMain = lastMainFocusedView?.get()
            if (lastMain != null &&
                lastMain.isAttachedToWindow &&
                lastMain.isShown &&
                FocusTreeUtils.isDescendantOf(lastMain, recyclerFollowing)
            ) {
                armSidebarCollapseAfterFocusTransfer()
                lastMain.requestFocus()
                return true
            }

            armSidebarCollapseAfterFocusTransfer()
            recyclerFollowing.post outer@{
                val cur = currentFocus
                if (cur != null && !isInSidebar(cur)) return@outer
                val vh = recyclerFollowing.findViewHolderForAdapterPosition(0)
                if (vh != null) {
                    vh.itemView.requestFocus()
                    return@outer
                }
                if (recyclerFollowing.adapter?.itemCount == 0) {
                    recyclerFollowing.requestFocus()
                    return@outer
                }
                recyclerFollowing.scrollToPosition(0)
                recyclerFollowing.post inner@{
                    val cur2 = currentFocus
                    if (cur2 != null && !isInSidebar(cur2)) return@inner
                    recyclerFollowing.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() ?: recyclerFollowing.requestFocus()
                }
            }
            return true
        }

        val lastMain = lastMainFocusedView?.get()
        val tabLayout = fragmentView.findViewById<com.google.android.material.tabs.TabLayout?>(R.id.tab_layout)
        val lastMainIsInTabs = tabLayout != null && lastMain != null && FocusTreeUtils.isDescendantOf(lastMain, tabLayout)
        if (!lastMainIsInTabs && lastMain != null && lastMain.isAttachedToWindow && lastMain.isShown && isInMainContainer(lastMain)) {
            armSidebarCollapseAfterFocusTransfer()
            lastMain.requestFocus()
            return true
        }

        // Prefer entering the current page content (cards) for TabLayout+ViewPager pages.
        // This avoids landing on the tab strip when content is available but not yet laid out.
        if (tabLayout?.isShown == true) {
            val host = rootFragment as? TabContentSwitchFocusHost
            if (host != null) {
                armSidebarCollapseAfterFocusTransfer()
                host.requestFocusCurrentPagePrimaryItemFromContentSwitch()
                return true
            }
        }

        val recycler =
            fragmentView.findViewById<RecyclerView?>(R.id.recycler_dynamic)
                ?: fragmentView.findViewById<RecyclerView?>(R.id.recycler)

        if (recycler != null) {
            armSidebarCollapseAfterFocusTransfer()
            recycler.post outer@{
                val cur = currentFocus
                if (cur != null && !isInSidebar(cur)) return@outer
                val vh = recycler.findViewHolderForAdapterPosition(0)
                if (vh != null) {
                    vh.itemView.requestFocus()
                    return@outer
                }
                recycler.scrollToPosition(0)
                recycler.post inner@{
                    val cur2 = currentFocus
                    if (cur2 != null && !isInSidebar(cur2)) return@inner
                    recycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() ?: recycler.requestFocus()
                }
            }
            return true
        }

        if (tabLayout != null) {
            val tabStrip = tabLayout.getChildAt(0) as? ViewGroup
            val pos = tabLayout.selectedTabPosition.takeIf { it >= 0 } ?: 0
            armSidebarCollapseAfterFocusTransfer()
            tabStrip?.getChildAt(pos)?.requestFocus()
            return true
        }

        val dynamicLoginBtn = fragmentView.findViewById<View?>(R.id.btn_login)
        if (dynamicLoginBtn != null && dynamicLoginBtn.isShown && dynamicLoginBtn.isFocusable) {
            armSidebarCollapseAfterFocusTransfer()
            dynamicLoginBtn.requestFocus()
            return true
        }

        armSidebarCollapseAfterFocusTransfer()
        fragmentView.requestFocus()
        return true
    }

    private fun prepareSidebarCollapseAfterMainFocus() {
        pendingSidebarCollapseAfterMainFocus = true
        val token = ++pendingSidebarCollapseToken
        binding.root.postDelayed(
            {
                if (pendingSidebarCollapseToken != token) return@postDelayed
                pendingSidebarCollapseAfterMainFocus = false
            },
            SIDEBAR_COLLAPSE_ARM_TIMEOUT_MS,
        )
    }

    private fun clearPendingSidebarCollapseAfterMainFocus() {
        pendingSidebarCollapseAfterMainFocus = false
        pendingSidebarCollapseToken++
    }

    private fun maybeCollapseSidebarAfterMainFocusTransfer(newFocus: View) {
        if (!pendingSidebarCollapseAfterMainFocus) return
        clearPendingSidebarCollapseAfterMainFocus()
        if (!BiliClient.prefs.mainAutoHideSidebarOnEnterContent) return
        if (!isInMainContainer(newFocus)) return
        setSidebarExpanded(expanded = false)
    }

    private fun setSidebarExpanded(expanded: Boolean) {
        if (isSidebarExpanded == expanded) return
        val mainLayoutParams = binding.mainContainer.layoutParams as? ConstraintLayout.LayoutParams ?: return
        if (expanded) {
            binding.sidebar.visibility = View.VISIBLE
            mainLayoutParams.startToStart = ConstraintLayout.LayoutParams.UNSET
            mainLayoutParams.startToEnd = R.id.sidebar
        } else {
            binding.sidebar.visibility = View.GONE
            mainLayoutParams.startToEnd = ConstraintLayout.LayoutParams.UNSET
            mainLayoutParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
        }
        binding.mainContainer.layoutParams = mainLayoutParams
        isSidebarExpanded = expanded
    }

    private fun focusSelectedTabInCurrentFragment(): Boolean {
        val fragmentView = currentRootFragment()?.view ?: return false
        val tabLayout = fragmentView.findViewById<com.google.android.material.tabs.TabLayout?>(R.id.tab_layout) ?: return false
        val tabStrip = tabLayout.getChildAt(0) as? ViewGroup ?: return false
        val pos = tabLayout.selectedTabPosition.takeIf { it >= 0 } ?: 0
        tabLayout.post { tabStrip.getChildAt(pos)?.requestFocus() }
        return true
    }

    private fun tryMoveDynamicVideoToFollowing(focused: View): Boolean {
        val fragmentView = currentRootFragment()?.view ?: return false
        val recyclerFollowing = fragmentView.findViewById<RecyclerView?>(R.id.recycler_following) ?: return false
        val recyclerDynamic = fragmentView.findViewById<RecyclerView?>(R.id.recycler_dynamic) ?: return false
        if (!FocusTreeUtils.isDescendantOf(focused, recyclerDynamic)) return false
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

        val inNav = FocusTreeUtils.isDescendantOf(focused, binding.recyclerSidebar)
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
        private const val STATE_KEY_ROOT_NAV_ID = "MainActivity.rootNavId"
        private const val BACK_DOUBLE_PRESS_WINDOW_MS = 1_500L
        private const val SIDEBAR_COLLAPSE_ARM_TIMEOUT_MS = 1_000L
    }
}
