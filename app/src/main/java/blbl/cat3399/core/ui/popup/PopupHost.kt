package blbl.cat3399.core.ui.popup

import androidx.activity.ComponentActivity
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import blbl.cat3399.R
import blbl.cat3399.core.ui.FocusReturn
import blbl.cat3399.core.ui.FocusTreeUtils
import blbl.cat3399.core.ui.userScaledContext
import java.lang.ref.WeakReference
import kotlin.math.max

internal class PopupHost private constructor(
    private val activity: ComponentActivity,
    private val contentRoot: FrameLayout,
    private val hostView: FrameLayout,
    private val modalLayer: FrameLayout,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private var consumeBackLikeKeyUp: Boolean = false

    private var systemBarsInsets = Insets.NONE
    private var imeInsets = Insets.NONE

    private var modalEntry: ModalEntry? = null

    // Focus "parking" view:
    // On TV / DPAD devices, dismissing a modal can temporarily detach the currently-focused view.
    // When that happens, the framework may pick a fallback focus target in the underlying UI
    // (e.g. a top bar "Back" button), resulting in a visible one-frame focus flicker.
    //
    // To prevent that, we temporarily move focus to an invisible, stable 1x1 view attached to the
    // popup host root. Once the real target regains focus, we disable the parking view again so it
    // does not participate in navigation.
    private var focusParkingView: View? = null
    private var focusParkingListener: ViewTreeObserver.OnGlobalFocusChangeListener? = null

    private data class FocusabilitySnapshot(
        val descendantFocusability: Int?,
        val isFocusable: Boolean,
        val isFocusableInTouchMode: Boolean,
    )

    private var underlyingFocusSnapshots: LinkedHashMap<View, FocusabilitySnapshot>? = null

    private data class ModalEntry(
        val rootView: View,
        val cancelable: Boolean,
        val preferredFocusView: View?,
        var lastFocusedView: WeakReference<View>?,
        val focusReturn: FocusReturn,
        val backCallback: OnBackPressedCallback,
        val focusListener: ViewTreeObserver.OnGlobalFocusChangeListener,
        val onDismiss: (() -> Unit)?,
        val onRestoreFocus: (() -> Boolean)?,
        var dismissing: Boolean = false,
    )

    private fun blockUnderlyingFocus() {
        if (underlyingFocusSnapshots != null) return
        val snapshots = LinkedHashMap<View, FocusabilitySnapshot>()
        for (i in 0 until contentRoot.childCount) {
            val child = contentRoot.getChildAt(i) ?: continue
            if (child === hostView) continue
            snapshots[child] =
                FocusabilitySnapshot(
                    descendantFocusability = (child as? ViewGroup)?.descendantFocusability,
                    isFocusable = child.isFocusable,
                    isFocusableInTouchMode = child.isFocusableInTouchMode,
                )
            (child as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            child.isFocusable = false
            child.isFocusableInTouchMode = false
        }
        underlyingFocusSnapshots = snapshots
    }

    private fun restoreUnderlyingFocus() {
        val snapshots = underlyingFocusSnapshots ?: return
        underlyingFocusSnapshots = null
        for ((view, snap) in snapshots) {
            (view as? ViewGroup)?.let { vg ->
                snap.descendantFocusability?.let { vg.descendantFocusability = it }
            }
            view.isFocusable = snap.isFocusable
            view.isFocusableInTouchMode = snap.isFocusableInTouchMode
        }
    }

    private fun ensureFocusParkingView(): View {
        val existing = focusParkingView
        if (existing != null) return existing

        val view =
            View(activity).apply {
                alpha = 0f
                isClickable = false
                isFocusable = false
                isFocusableInTouchMode = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        hostView.addView(
            view,
            FrameLayout.LayoutParams(
                /* width= */ 1,
                /* height= */ 1,
                /* gravity= */ Gravity.START or Gravity.TOP,
            ),
        )
        focusParkingView = view
        return view
    }

    /**
     * Move focus to an invisible, stable view that stays attached while the modal is being dismissed.
     *
     * This prevents a system-level "fallback focus target" during view detach/remove (which can be
     * visible as a brief focus jump to an unrelated control).
     *
     * The view is enabled only temporarily and is automatically disabled as soon as focus moves
     * elsewhere.
     */
    private fun parkFocusForRestore() {
        val parking = ensureFocusParkingView()
        parking.isFocusable = true
        parking.isFocusableInTouchMode = true

        if (focusParkingListener == null) {
            val listener =
                ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
                    val v = focusParkingView ?: return@OnGlobalFocusChangeListener
                    if (newFocus == null || newFocus === v) return@OnGlobalFocusChangeListener
                    disableFocusParking()
                }
            focusParkingListener = listener
            runCatching { contentRoot.viewTreeObserver.addOnGlobalFocusChangeListener(listener) }
        }

        // Best-effort: even if focus restore fails (e.g. underlying list is updating),
        // keeping focus on this invisible view prevents the system from temporarily
        // falling back to an unrelated control (like the player's Back button).
        if (!parking.requestFocus()) {
            parking.post { parking.requestFocus() }
        }
    }

    /**
     * Disable the parking view so it never becomes a navigation target.
     */
    private fun disableFocusParking() {
        focusParkingView?.let { v ->
            v.isFocusable = false
            v.isFocusableInTouchMode = false
        }

        val listener = focusParkingListener
        focusParkingListener = null
        if (listener != null) {
            runCatching { contentRoot.viewTreeObserver.removeOnGlobalFocusChangeListener(listener) }
        }
    }

    fun showModal(
        title: CharSequence?,
        contentView: View,
        cancelable: Boolean,
        actions: List<PopupAction>,
        preferredActionRole: PopupActionRole? = PopupActionRole.POSITIVE,
        autoFocus: Boolean = true,
        onModalAttached: ((modalRoot: View) -> Unit)? = null,
        onDismiss: (() -> Unit)? = null,
        onRestoreFocus: (() -> Boolean)? = null,
    ): PopupHandle {
        checkMainThread()

        // Prevent focus search from escaping into the underlying UI (common on TV / DPAD devices,
        // especially older Android versions). Without this, pressing DPAD can momentarily focus
        // a control behind the modal and then get pulled back by the focus trap, visible as a
        // "focus flash" in the background.
        blockUnderlyingFocus()

        // When a modal triggers another modal (i.e. we replace the currently visible modal),
        // preserve the original FocusReturn target so the final dismiss restores focus back to
        // where the user was before the whole modal flow started.
        val inheritedFocusReturn = modalEntry?.focusReturn
        if (inheritedFocusReturn != null) {
            // Disable the focus trap before moving focus out of the old modal.
            modalEntry?.dismissing = true
            // While the old modal is being removed, keep focus on a stable view to prevent a
            // system-level fallback focus target (e.g. a top bar Back button) for a brief frame.
            parkFocusForRestore()
        }
        dismissModalInternal(animate = false, restoreFocus = false)

        val dialogContext = activity.userScaledContext()
        val root =
            LayoutInflater.from(dialogContext)
                .inflate(R.layout.view_popup_modal, modalLayer, false)

        val overlay = root.findViewById<View>(R.id.overlay)
        val card = root.findViewById<View>(R.id.card)
        val tvTitle = root.findViewById<TextView>(R.id.tv_title)
        val content = root.findViewById<FrameLayout>(R.id.content)
        val actionsRow = root.findViewById<LinearLayout>(R.id.actions)

        // The overlay should intercept touch (outside click), but must not take focus.
        overlay.isFocusable = false
        overlay.isFocusableInTouchMode = false

        tvTitle.text = title
        val hasTitle = !title.isNullOrBlank()
        tvTitle.visibility = if (hasTitle) View.VISIBLE else View.GONE
        if (!hasTitle) {
            (content.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.topMargin = 0
                content.layoutParams = lp
            }
        }

        content.removeAllViews()
        content.addView(
            contentView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        // Consume clicks inside the card so "outside click to dismiss" works reliably.
        card.isClickable = true
        card.isFocusable = false
        card.isFocusableInTouchMode = false
        (card as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        card.setOnClickListener { /* consume */ }
        actionsRow.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        val focusReturn =
            inheritedFocusReturn
                ?: FocusReturn().also { it.capture(activity.window?.decorView?.findFocus()) }

        val backCallback =
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (cancelable) dismissModal()
                }
            }
        activity.onBackPressedDispatcher.addCallback(backCallback)

        // Actions
        actionsRow.removeAllViews()
        actionsRow.visibility = if (actions.isEmpty()) View.GONE else View.VISIBLE
        var preferredFocusView: View? = null
        val actionViewsByRole = HashMap<PopupActionRole, View>(3)
        val actionViews = ArrayList<View>(actions.size)
        for ((idx, a) in actions.withIndex()) {
            val btn =
                LayoutInflater.from(dialogContext)
                .inflate(R.layout.item_popup_action, actionsRow, false)
            btn.id = View.generateViewId()
            val tv = btn.findViewById<TextView>(R.id.tv_text)
            tv.text = a.text
            btn.setOnClickListener {
                a.onClick?.invoke()
                if (a.dismissOnClick) {
                    // If the click handler opened/replaced the modal, do not dismiss the new one.
                    // Only dismiss if this modal is still the current modal.
                    val current = modalEntry
                    if (current != null && current.rootView === overlay) dismissModal()
                }
            }
            val lp =
                (btn.layoutParams as? ViewGroup.MarginLayoutParams)
                    ?: ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
            if (idx > 0) lp.marginStart = dp(dialogContext, 10f)
            btn.layoutParams = lp
            actionsRow.addView(btn)
            actionViews += btn
            if (!actionViewsByRole.containsKey(a.role)) actionViewsByRole[a.role] = btn
        }
        wireActionButtonFocus(actionViews)

        if (preferredActionRole != null) {
            preferredFocusView = actionViewsByRole[preferredActionRole]
        }
        if (preferredFocusView == null) preferredFocusView = actionViewsByRole[PopupActionRole.POSITIVE]
        if (preferredFocusView == null) preferredFocusView = actionViewsByRole[PopupActionRole.NEUTRAL]
        if (preferredFocusView == null) preferredFocusView = actionViewsByRole[PopupActionRole.NEGATIVE]
        if (preferredFocusView == null) preferredFocusView = actionsRow.getChildAt(0)

        overlay.setOnClickListener {
            if (cancelable) dismissModal()
        }

        // Width + insets.
        applyModalSizeAndInsets(dialogContext = dialogContext, modalRoot = overlay, card = card)

        // Focus trap: when a modal is visible, keep focus within it.
        val focusListener =
            ViewTreeObserver.OnGlobalFocusChangeListener { oldFocus, newFocus ->
                val entry = modalEntry
                if (entry == null) return@OnGlobalFocusChangeListener
                // During dismiss we intentionally park focus outside the modal; do not trap it back in.
                if (entry.dismissing) return@OnGlobalFocusChangeListener
                val modalRoot = entry.rootView

                if (!modalRoot.isAttachedToWindow) return@OnGlobalFocusChangeListener
                if (newFocus != null && FocusTreeUtils.isDescendantOf(newFocus, modalRoot)) {
                    entry.lastFocusedView = WeakReference(newFocus)
                    return@OnGlobalFocusChangeListener
                }
                if (newFocus == null) {
                    // Some devices clear focus temporarily while scrolling/recycling.
                    // Bring focus back on the next frame (best-effort).
                    if (oldFocus != null && FocusTreeUtils.isDescendantOf(oldFocus, modalRoot)) {
                        entry.lastFocusedView = WeakReference(oldFocus)
                    }
                    modalRoot.post {
                        if (modalEntry !== entry) return@post
                        restoreModalFocus(entry)
                    }
                    return@OnGlobalFocusChangeListener
                }

                // Bring focus back to the dialog (best-effort).
                modalRoot.post {
                    if (modalEntry !== entry) return@post
                    restoreModalFocus(entry)
                }
            }

        contentRoot.viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)

        // Show
        modalLayer.addView(
            root,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        onModalAttached?.invoke(overlay)

        // Animate in.
        overlay.alpha = 0f
        card.alpha = 0f
        card.scaleX = 0.96f
        card.scaleY = 0.96f
        overlay.animate().alpha(1f).setDuration(160L).start()
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180L).start()

        val entry =
            ModalEntry(
                rootView = overlay,
                cancelable = cancelable,
                preferredFocusView = preferredFocusView,
                lastFocusedView = null,
                focusReturn = focusReturn,
                backCallback = backCallback,
                focusListener = focusListener,
                onDismiss = onDismiss,
                onRestoreFocus = onRestoreFocus,
            )
        modalEntry = entry

        // Focus after layout. Some TV devices can choose the card/container if focus is requested too
        // early, so retry once after the modal has been attached and measured.
        if (autoFocus) {
            overlay.post {
                if (modalEntry === entry) restoreModalFocus(entry)
            }
            overlay.postDelayed(
                {
                    if (modalEntry === entry && !isFocusInsideModal(entry.rootView)) {
                        restoreModalFocus(entry)
                    }
                },
                80L,
            )
        }

        return object : PopupHandle {
            override val isShowing: Boolean
                get() = modalEntry?.rootView === overlay && overlay.isAttachedToWindow

            override fun dismiss() {
                if (Looper.myLooper() != Looper.getMainLooper()) {
                    mainHandler.post { dismiss() }
                    return
                }
                if (modalEntry?.rootView !== overlay) return
                dismissModal()
            }
        }
    }

    fun dismissModal() {
        checkMainThread()
        dismissModalInternal(animate = true, restoreFocus = true)
    }

    fun applyInsets(insets: WindowInsetsCompat) {
        systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
        modalEntry?.rootView?.let { modalRoot ->
            val card = modalRoot.findViewById<View>(R.id.card) ?: return@let
            applyModalSizeAndInsets(dialogContext = modalRoot.context, modalRoot = modalRoot, card = card)
        }
    }

    private fun applyModalSizeAndInsets(dialogContext: android.content.Context, modalRoot: View, card: View) {
        val bottom = max(systemBarsInsets.bottom, imeInsets.bottom)
        modalRoot.setPadding(systemBarsInsets.left, systemBarsInsets.top, systemBarsInsets.right, bottom)

        val dm = dialogContext.resources.displayMetrics
        val maxWidthPx = dp(dialogContext, 600f)
        val targetWidthPx = (dm.widthPixels * 0.90f).toInt().coerceAtMost(maxWidthPx).coerceAtLeast(1)
        val targetHeightPx = (dm.heightPixels * 0.90f).toInt().coerceAtLeast(1)
        (card.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            if (lp.width != targetWidthPx) {
                lp.width = targetWidthPx
                card.layoutParams = lp
            }
        }
        (card.findViewById<View>(R.id.container) as? PopupModalLayout)?.maxHeightPx = targetHeightPx
    }

    private fun dp(context: android.content.Context, value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()

    private fun findFirstFocusableDescendant(root: View): View? {
        if (root.isShown && root.isFocusable) return root
        val vg = root as? ViewGroup ?: return null
        for (i in 0 until vg.childCount) {
            val child = vg.getChildAt(i) ?: continue
            findFirstFocusableDescendant(child)?.let { return it }
        }
        return null
    }

    private fun wireActionButtonFocus(actionViews: List<View>) {
        for ((index, view) in actionViews.withIndex()) {
            val left = actionViews.getOrNull(index - 1)
            val right = actionViews.getOrNull(index + 1)
            view.nextFocusLeftId = (left ?: view).id
            view.nextFocusRightId = (right ?: view).id
        }
    }

    private fun isFocusInsideModal(modalRoot: View): Boolean {
        val focused = activity.window?.decorView?.findFocus() ?: return false
        return FocusTreeUtils.isDescendantOf(focused, modalRoot)
    }

    private fun restoreModalFocus(entry: ModalEntry) {
        val modalRoot = entry.rootView
        if (!modalRoot.isAttachedToWindow) return

        val last = entry.lastFocusedView?.get()
        if (last != null && last.isAttachedToWindow && last.isShown && last.isFocusable && last.requestFocus()) return

        val desired = entry.preferredFocusView
        if (desired != null && desired.isAttachedToWindow && desired.isShown && desired.isFocusable && desired.requestFocus()) return

        val cardRoot = modalRoot.findViewById<View>(R.id.card) ?: modalRoot
        if (findFirstFocusableDescendant(cardRoot)?.requestFocus() == true) return
    }

    private fun dismissModalInternal(
        animate: Boolean,
        restoreFocus: Boolean,
    ) {
        checkMainThread()
        val entry = modalEntry ?: return
        val root = entry.rootView
        val card = root.findViewById<View>(R.id.card) ?: root

        fun finalizeDismiss() {
            if (modalEntry === entry) {
                modalEntry = null
            }
            runCatching { entry.backCallback.remove() }
            runCatching { contentRoot.viewTreeObserver.removeOnGlobalFocusChangeListener(entry.focusListener) }

            if (restoreFocus) {
                // Re-enable underlying focusability before invoking callbacks or restoring focus.
                // Some dismiss callbacks refresh the underlying UI and may request focus.
                restoreUnderlyingFocus()
            }

            // Invoke callbacks before restoring focus to avoid causing a focus "double jump" when
            // the underlying UI updates (e.g. list refresh) and would otherwise temporarily lose focus.
            entry.onDismiss?.invoke()

            // If the dismiss callback immediately opened another modal, do not steal focus back.
            val openedNewModal = modalLayer.childCount > 1
            if (restoreFocus && !openedNewModal) {
                // Re-park focus right before restoring:
                // - Covers the non-animated dismiss path.
                // - In animated dismiss, focus might have moved while the modal was fading out.
                parkFocusForRestore()
                val restoredByCallback = runCatching { entry.onRestoreFocus?.invoke() }.getOrNull() == true
                if (!restoredByCallback) entry.focusReturn.restoreAndClear()
            }
            runCatching { (root.parent as? ViewGroup)?.removeView(root) }
        }

        if (!animate) {
            root.animate().cancel()
            card.animate().cancel()
            finalizeDismiss()
            return
        }

        // Idempotency: if we are already dismissing, do not cancel the running animations.
        // Canceling the animator would also cancel its end-action, leaving an invisible but
        // still-attached overlay that blocks all input.
        if (entry.dismissing) return

        root.animate().cancel()
        card.animate().cancel()
        entry.dismissing = true

        if (restoreFocus) {
            // While the modal is being removed, the currently focused view inside it may get detached.
            // If focus becomes temporarily null, the system can "fallback" to an unrelated control
            // (e.g. the player's Back button), causing a visible one-frame focus flicker.
            // Park focus on an invisible view early so the modal can disappear without triggering
            // a system-level fallback focus target.
            parkFocusForRestore()
        }

        // Animate out then remove.
        // During dismiss we disable the focus trap (see focusListener early-return) and instead
        // rely on focus parking to prevent a system-level fallback focus target flicker.
        root.animate().alpha(0f).setDuration(140L).start()
        card.animate()
            .alpha(0f)
            .scaleX(0.98f)
            .scaleY(0.98f)
            .setDuration(140L)
            .withEndAction {
                finalizeDismiss()
            }
            .start()

        // Fallback: in rare cases the end-action may not fire (e.g. animation canceled externally).
        // Ensure we eventually tear down the modal so it cannot block input forever.
        mainHandler.postDelayed(
            {
                if (modalEntry === entry) finalizeDismiss()
            },
            /* delayMillis = */ 220L,
        )
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "PopupHost must be used on main thread." }
    }

    fun hasModalView(): Boolean {
        return modalLayer.childCount > 0
    }

    fun consumeBackLikeKeyEventIfNeeded(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val isBackLike =
            keyCode == KeyEvent.KEYCODE_BACK ||
                keyCode == KeyEvent.KEYCODE_ESCAPE ||
                keyCode == KeyEvent.KEYCODE_BUTTON_B

        if (!isBackLike) return false

        if (event.action == KeyEvent.ACTION_UP && consumeBackLikeKeyUp) {
            consumeBackLikeKeyUp = false
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false

        if (!hasModalView()) return false

        // Consume the matching UP even if the modal is already dismissed (or dismissing).
        consumeBackLikeKeyUp = true

        val entry = modalEntry
        if (entry != null && entry.cancelable) dismissModal()
        return true
    }

    companion object {
        fun from(activity: ComponentActivity): PopupHost {
            val contentRoot = activity.findViewById<FrameLayout>(android.R.id.content)
            val existing = contentRoot.getTag(R.id.tag_popup_host) as? PopupHost
            if (existing != null) return existing

            val hostView =
                FrameLayout(activity).apply {
                    layoutParams =
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                        )
                    clipChildren = false
                    clipToPadding = false
                    isClickable = false
                    isFocusable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }

            val modalLayer =
                FrameLayout(activity).apply {
                    layoutParams =
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                        )
                    clipChildren = false
                    clipToPadding = false
                    isClickable = false
                    isFocusable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }

            hostView.addView(modalLayer)
            contentRoot.addView(hostView)

            val host = PopupHost(activity = activity, contentRoot = contentRoot, hostView = hostView, modalLayer = modalLayer)
            contentRoot.setTag(R.id.tag_popup_host, host)

            ViewCompat.setOnApplyWindowInsetsListener(hostView) { _, insets ->
                host.applyInsets(insets)
                insets
            }
            ViewCompat.requestApplyInsets(hostView)

            return host
        }

        fun peek(activity: ComponentActivity): PopupHost? {
            val contentRoot = activity.findViewById<FrameLayout>(android.R.id.content)
            return contentRoot.getTag(R.id.tag_popup_host) as? PopupHost
        }
    }
}
