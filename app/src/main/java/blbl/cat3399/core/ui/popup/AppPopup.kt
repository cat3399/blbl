package blbl.cat3399.core.ui.popup

import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.R
import blbl.cat3399.core.ui.userScaledContext
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlin.math.max

object AppPopup {
    private const val DESIRED_VISIBLE_ROWS: Int = 7
    private const val MAX_DIALOG_HEIGHT_RATIO: Float = 0.90f
    private val mainHandler = Handler(Looper.getMainLooper())

    fun message(
        context: Context,
        title: CharSequence? = null,
        message: CharSequence,
        positiveText: CharSequence = "确定",
        cancelable: Boolean = true,
        onPositive: (() -> Unit)? = null,
        onDismiss: (() -> Unit)? = null,
    ): PopupHandle? {
        return runOnMainOrPost {
            val activity = context.findActivity() ?: return@runOnMainOrPost null
            val dialogContext = activity.userScaledContext()
            val tv =
                LayoutInflater.from(dialogContext)
                    .inflate(R.layout.view_popup_message, null, false) as TextView
            tv.text = message
            PopupHost.from(activity).showModal(
                title = title,
                contentView = tv,
                cancelable = cancelable,
                actions =
                    listOf(
                        PopupAction(role = PopupActionRole.POSITIVE, text = positiveText) { onPositive?.invoke() },
                    ),
                preferredActionRole = PopupActionRole.POSITIVE,
                onDismiss = onDismiss,
            )
        }
    }

    fun confirm(
        context: Context,
        title: CharSequence? = null,
        message: CharSequence,
        positiveText: CharSequence = "确定",
        negativeText: CharSequence = "取消",
        cancelable: Boolean = true,
        onPositive: (() -> Unit)? = null,
        onNegative: (() -> Unit)? = null,
        onDismiss: (() -> Unit)? = null,
    ): PopupHandle? {
        return runOnMainOrPost {
            val activity = context.findActivity() ?: return@runOnMainOrPost null
            val dialogContext = activity.userScaledContext()
            val tv =
                LayoutInflater.from(dialogContext)
                    .inflate(R.layout.view_popup_message, null, false) as TextView
            tv.text = message
            PopupHost.from(activity).showModal(
                title = title,
                contentView = tv,
                cancelable = cancelable,
                actions =
                    listOf(
                        PopupAction(role = PopupActionRole.NEGATIVE, text = negativeText) { onNegative?.invoke() },
                        PopupAction(role = PopupActionRole.POSITIVE, text = positiveText) { onPositive?.invoke() },
                    ),
                preferredActionRole = PopupActionRole.POSITIVE,
                onDismiss = onDismiss,
            )
        }
    }

    fun input(
        context: Context,
        title: CharSequence,
        initial: String,
        hint: CharSequence? = null,
        inputType: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL,
        minLines: Int = 1,
        positiveText: CharSequence = "保存",
        negativeText: CharSequence = "取消",
        neutralText: CharSequence? = null,
        cancelable: Boolean = true,
        onPositive: (text: String) -> Unit,
        onNegative: (() -> Unit)? = null,
        onNeutral: (() -> Unit)? = null,
        validate: ((text: String) -> Boolean)? = null,
        onDismiss: (() -> Unit)? = null,
    ): PopupHandle? {
        return runOnMainOrPost {
            val activity = context.findActivity() ?: return@runOnMainOrPost null
            val dialogContext = activity.userScaledContext()

            val inputView =
                EditText(dialogContext).apply {
                    setText(initial)
                    setSelection(text?.length ?: 0)
                    this.hint = hint
                    this.inputType = inputType
                    this.minLines = minLines.coerceAtLeast(1)
                }

            var handle: PopupHandle? = null

            val actions = ArrayList<PopupAction>(3)
            if (neutralText != null) {
                actions.add(
                    PopupAction(role = PopupActionRole.NEUTRAL, text = neutralText) { onNeutral?.invoke() },
                )
            }
            actions.add(
                PopupAction(role = PopupActionRole.NEGATIVE, text = negativeText) { onNegative?.invoke() },
            )
            actions.add(
                PopupAction(
                    role = PopupActionRole.POSITIVE,
                    text = positiveText,
                    dismissOnClick = false,
                ) {
                    val text = inputView.text?.toString().orEmpty()
                    val ok = validate?.invoke(text) ?: true
                    if (!ok) return@PopupAction
                    onPositive(text)
                    handle?.dismiss()
                },
            )

            handle =
                PopupHost.from(activity).showModal(
                    title = title,
                    contentView = inputView,
                    cancelable = cancelable,
                    actions = actions,
                    preferredActionRole = PopupActionRole.POSITIVE,
                    autoFocus = false,
                    onModalAttached = {
                        inputView.post { inputView.requestFocus() }
                    },
                    onDismiss = onDismiss,
                )

            handle
        }
    }

    fun singleChoice(
        context: Context,
        title: CharSequence,
        items: List<String>,
        checkedIndex: Int,
        cancelable: Boolean = true,
        onDismiss: (() -> Unit)? = null,
        onRestoreFocus: (() -> Boolean)? = null,
        onPicked: (index: Int, label: String) -> Unit,
    ): PopupHandle? {
        return runOnMainOrPost {
            val activity = context.findActivity() ?: return@runOnMainOrPost null
            val dialogContext = activity.userScaledContext()

            if (items.isEmpty()) {
                return@runOnMainOrPost message(
                    context = activity,
                    title = title,
                    message = "暂无可选项",
                    positiveText = "关闭",
                    cancelable = true,
                    onDismiss = onDismiss,
                )
            }

            val view =
                LayoutInflater.from(dialogContext)
                    .inflate(R.layout.view_popup_choice_list, null, false)
            val recycler = view.findViewById<RecyclerView>(R.id.recycler)
            recycler.layoutManager = LinearLayoutManager(dialogContext)

            val safeChecked = checkedIndex.takeIf { it in items.indices } ?: 0
            recycler.scrollToPosition(safeChecked)

            var handle: PopupHandle? = null
            val adapter =
                SingleChoiceAdapter(
                    items = items,
                    checkedIndex = safeChecked,
                    onPick = { index ->
                        val label = items.getOrNull(index).orEmpty()
                        onPicked(index, label)
                        handle?.dismiss()
                    },
                )
            recycler.adapter = adapter

            handle =
                PopupHost.from(activity).showModal(
                    title = title,
                    contentView = view,
                    cancelable = cancelable,
                    actions = emptyList(),
                    preferredActionRole = null,
                    autoFocus = false,
                    onModalAttached = { modalRoot ->
                        applyManagedListLayout(
                            modalRoot = modalRoot,
                            recycler = recycler,
                            itemCount = items.size,
                            focusIndex = safeChecked,
                        )
                    },
                    onDismiss = onDismiss,
                    onRestoreFocus = onRestoreFocus,
                )

            handle
        }
    }

    fun multiChoice(
        context: Context,
        title: CharSequence,
        items: List<String>,
        checked: BooleanArray,
        cancelable: Boolean = true,
        minCheckedCount: Int = 0,
        onChanged: (checked: BooleanArray) -> Unit,
        onDismiss: (() -> Unit)? = null,
    ): PopupHandle? {
        return runOnMainOrPost {
            val activity = context.findActivity() ?: return@runOnMainOrPost null
            val dialogContext = activity.userScaledContext()

            if (!cancelable) {
                // Without action buttons this would have no exit. Use custom() if you need a non-cancelable multi-choice flow.
                return@runOnMainOrPost message(
                    context = activity,
                    title = title,
                    message = "该选项列表必须允许取消/返回，否则无法退出。",
                    positiveText = "关闭",
                    cancelable = true,
                    onDismiss = onDismiss,
                )
            }

            if (items.isEmpty()) {
                return@runOnMainOrPost message(
                    context = activity,
                    title = title,
                    message = "暂无可选项",
                    positiveText = "关闭",
                    cancelable = true,
                    onDismiss = onDismiss,
                )
            }

            val view =
                LayoutInflater.from(dialogContext)
                    .inflate(R.layout.view_popup_choice_list, null, false)
            val recycler = view.findViewById<RecyclerView>(R.id.recycler)
            recycler.layoutManager = LinearLayoutManager(dialogContext)

            val state = checked.copyOf(items.size)
            val focusIndex = state.indexOfFirst { it }.takeIf { it in items.indices } ?: 0
            recycler.scrollToPosition(focusIndex)

            val adapter =
                MultiChoiceAdapter(
                    items = items,
                    checked = state,
                    minCheckedCount = minCheckedCount.coerceAtLeast(0),
                    onChanged = { onChanged(state.copyOf()) },
                )
            recycler.adapter = adapter

            PopupHost.from(activity).showModal(
                title = title,
                contentView = view,
                cancelable = cancelable,
                actions = emptyList(),
                preferredActionRole = null,
                autoFocus = false,
                onModalAttached = { modalRoot ->
                    applyManagedListLayout(
                        modalRoot = modalRoot,
                        recycler = recycler,
                        itemCount = items.size,
                        focusIndex = focusIndex,
                    )
                },
                onDismiss = onDismiss,
            )
        }
    }

    interface ProgressHandle : PopupHandle {
        fun updateStatus(text: CharSequence)

        /**
         * @param percent null means indeterminate.
         */
        fun updateProgress(percent: Int?)
    }

    fun progress(
        context: Context,
        title: CharSequence,
        status: CharSequence,
        negativeText: CharSequence = "取消",
        cancelable: Boolean = false,
        onNegative: (() -> Unit)? = null,
        onDismiss: (() -> Unit)? = null,
    ): ProgressHandle? {
        requireMainThread()
        val activity = context.findActivity() ?: return null
        val dialogContext = activity.userScaledContext()

        val view =
            LayoutInflater.from(dialogContext)
                .inflate(R.layout.dialog_test_update_progress, null, false)
        val tvStatus = view.findViewById<TextView>(R.id.tv_status)
        val progress = view.findViewById<LinearProgressIndicator>(R.id.progress)
        tvStatus.text = status
        progress.isIndeterminate = true

        val popup =
            PopupHost.from(activity).showModal(
                title = title,
                contentView = view,
                cancelable = cancelable,
                actions =
                    listOf(
                        PopupAction(role = PopupActionRole.NEGATIVE, text = negativeText) { onNegative?.invoke() },
                    ),
                preferredActionRole = PopupActionRole.NEGATIVE,
                autoFocus = true,
                onDismiss = onDismiss,
            )

        return object : ProgressHandle {
            override val isShowing: Boolean
                get() = popup.isShowing

            override fun dismiss() {
                popup.dismiss()
            }

            override fun updateStatus(text: CharSequence) {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    if (!isShowing) return
                    tvStatus.text = text
                } else {
                    mainHandler.post { updateStatus(text) }
                }
            }

            override fun updateProgress(percent: Int?) {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    if (!isShowing) return
                    if (percent == null) {
                        progress.isIndeterminate = true
                    } else {
                        progress.isIndeterminate = false
                        progress.progress = percent.coerceIn(0, 100)
                    }
                } else {
                    mainHandler.post { updateProgress(percent) }
                }
            }
        }
    }

    fun custom(
        context: Context,
        title: CharSequence? = null,
        cancelable: Boolean = true,
        actions: List<PopupAction>,
        preferredActionRole: PopupActionRole? = PopupActionRole.POSITIVE,
        autoFocus: Boolean = true,
        onModalAttached: ((modalRoot: View) -> Unit)? = null,
        onDismiss: (() -> Unit)? = null,
        content: (dialogContext: Context) -> View,
    ): PopupHandle? {
        return runOnMainOrPost {
            val activity = context.findActivity() ?: return@runOnMainOrPost null
            val dialogContext = activity.userScaledContext()
            val contentView = content(dialogContext)
            PopupHost.from(activity).showModal(
                title = title,
                contentView = contentView,
                cancelable = cancelable,
                actions = actions,
                preferredActionRole = preferredActionRole,
                autoFocus = autoFocus,
                onModalAttached = onModalAttached,
                onDismiss = onDismiss,
            )
        }
    }

    fun applyManagedListLayout(
        modalRoot: View,
        recycler: RecyclerView,
        itemCount: Int,
        focusIndex: Int = 0,
    ) {
        val card = modalRoot.findViewById<View>(R.id.card) ?: modalRoot
        applyChoiceDialogPreDraw(
            context = modalRoot.context,
            card = card,
            recycler = recycler,
            itemCount = itemCount,
            focusIndex = focusIndex,
        )
    }

    private fun applyChoiceDialogPreDraw(
        context: Context,
        card: View,
        recycler: RecyclerView,
        itemCount: Int,
        focusIndex: Int,
    ) {
        val decor = card
        decor.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                private var attempts: Int = 0

                override fun onPreDraw(): Boolean {
                    if (!decor.viewTreeObserver.isAlive) return true
                    attempts++
                    val ok =
                        applyDesiredListHeight(
                            context = context,
                            card = card,
                            recycler = recycler,
                            itemCount = itemCount,
                            focusIndex = focusIndex,
                        )
                    if (!ok) {
                        if (attempts >= 3) {
                            decor.viewTreeObserver.removeOnPreDrawListener(this)
                            return true
                        }
                        return false
                    }
                    decor.viewTreeObserver.removeOnPreDrawListener(this)
                    return false
                }
            },
        )
    }

    private fun applyDesiredListHeight(
        context: Context,
        card: View,
        recycler: RecyclerView,
        itemCount: Int,
        focusIndex: Int,
    ): Boolean {
        val count = itemCount.coerceAtLeast(0)
        if (count <= 0) return true
        val rows = DESIRED_VISIBLE_ROWS.coerceAtLeast(1).coerceAtMost(count)

        val dm = context.resources.displayMetrics
        val maxDialogHeightPx = (dm.heightPixels * MAX_DIALOG_HEIGHT_RATIO).toInt().coerceAtLeast(1)

        val child = recycler.getChildAt(0)
        val childLp = child?.layoutParams as? ViewGroup.MarginLayoutParams
        val measuredRowHeightPx =
            child
                ?.height
                ?.takeIf { it > 0 }
                ?.let { it + (childLp?.topMargin ?: 0) + (childLp?.bottomMargin ?: 0) }
        if (measuredRowHeightPx == null) return false

        val rowHeightPx = measuredRowHeightPx.coerceAtLeast(1)
        val desiredListHeightRaw = (rowHeightPx * rows) + recycler.paddingTop + recycler.paddingBottom

        val currentCardHeight = card.height.takeIf { it > 0 } ?: 0
        val currentListHeight = recycler.height.takeIf { it > 0 } ?: 0
        val nonListHeight = (currentCardHeight - currentListHeight).coerceAtLeast(0)
        val maxListHeight = (maxDialogHeightPx - nonListHeight).coerceAtLeast(rowHeightPx)
        val desiredListHeightPx = desiredListHeightRaw.coerceAtMost(maxListHeight)

        val lp =
            recycler.layoutParams
                ?: ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (lp.height != desiredListHeightPx) {
            lp.height = desiredListHeightPx
            recycler.layoutParams = lp
        }

        val safeFocus = focusIndex.coerceIn(0, count - 1)
        recycler.scrollToPosition(safeFocus)
        (recycler.findViewHolderForAdapterPosition(safeFocus)?.itemView ?: recycler.getChildAt(0))?.requestFocus()
        return true
    }

    private fun dp(context: Context, value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()

    private fun Context.findActivity(): ComponentActivity? {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is ComponentActivity) return current
            current = current.baseContext
        }
        return current as? ComponentActivity
    }

    private fun <T> runOnMainOrPost(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        mainHandler.post { block() }
        return null
    }

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "This API must be called on the main thread." }
    }

    private class SingleChoiceAdapter(
        private val items: List<String>,
        private var checkedIndex: Int,
        private val onPick: (index: Int) -> Unit,
    ) : RecyclerView.Adapter<SingleChoiceAdapter.Vh>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_popup_choice, parent, false)
            return Vh(view)
        }

        override fun onBindViewHolder(holder: Vh, position: Int) {
            val label = items.getOrNull(position).orEmpty()
            holder.bind(
                label = label,
                checked = position == checkedIndex,
                position = position,
                itemCount = items.size,
                onClick = {
                    checkedIndex = position
                    notifyDataSetChanged()
                    onPick(position)
                },
            )
        }

        override fun getItemCount(): Int = items.size

        class Vh(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvLabel: TextView = itemView.findViewById(R.id.tv_label)
            private val tvCheck: TextView = itemView.findViewById(R.id.tv_check)

            fun bind(label: String, checked: Boolean, position: Int, itemCount: Int, onClick: () -> Unit) {
                tvLabel.text = label
                tvCheck.visibility = if (checked) View.VISIBLE else View.GONE
                itemView.setOnClickListener { onClick() }
                itemView.setOnKeyListener { _, keyCode, event ->
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.KEYCODE_DPAD_RIGHT,
                        -> true

                        KeyEvent.KEYCODE_DPAD_UP -> position <= 0
                        KeyEvent.KEYCODE_DPAD_DOWN -> position >= (itemCount - 1)

                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        ->
                            if (event.action == KeyEvent.ACTION_UP) {
                                onClick()
                                true
                            } else {
                                false
                            }

                        else -> false
                    }
                }
            }
        }
    }

    private class MultiChoiceAdapter(
        private val items: List<String>,
        private val checked: BooleanArray,
        private val minCheckedCount: Int,
        private val onChanged: (() -> Unit)?,
    ) : RecyclerView.Adapter<MultiChoiceAdapter.Vh>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_popup_choice, parent, false)
            return Vh(view)
        }

        override fun onBindViewHolder(holder: Vh, position: Int) {
            val label = items.getOrNull(position).orEmpty()
            holder.bind(
                label = label,
                checked = checked.getOrNull(position) == true,
                position = position,
                itemCount = items.size,
                onToggle = {
                    if (position !in checked.indices) return@bind
                    if (checked[position] && checked.count { it } <= minCheckedCount) return@bind
                    checked[position] = !checked[position]
                    notifyItemChanged(position)
                    onChanged?.invoke()
                },
            )
        }

        override fun getItemCount(): Int = items.size

        private fun BooleanArray.getOrNull(index: Int): Boolean? = if (index in indices) this[index] else null

        class Vh(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvLabel: TextView = itemView.findViewById(R.id.tv_label)
            private val tvCheck: TextView = itemView.findViewById(R.id.tv_check)

            fun bind(label: String, checked: Boolean, position: Int, itemCount: Int, onToggle: () -> Unit) {
                tvLabel.text = label
                tvCheck.visibility = if (checked) View.VISIBLE else View.GONE
                itemView.setOnClickListener { onToggle() }
                itemView.setOnKeyListener { _, keyCode, event ->
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.KEYCODE_DPAD_RIGHT,
                        -> true

                        KeyEvent.KEYCODE_DPAD_UP -> position <= 0
                        KeyEvent.KEYCODE_DPAD_DOWN -> position >= (itemCount - 1)

                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        ->
                            if (event.action == KeyEvent.ACTION_UP) {
                                onToggle()
                                true
                            } else {
                                false
                            }

                        else -> false
                    }
                }
            }
        }
    }
}
