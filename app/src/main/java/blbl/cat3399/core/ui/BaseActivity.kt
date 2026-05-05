package blbl.cat3399.core.ui

import android.app.Activity
import android.content.Context
import android.os.BadParcelableException
import android.os.Bundle
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.theme.ThemePresets

open class BaseActivity : AppCompatActivity() {
    private var createdUiScaleFactor: Float? = null
    private var pendingUiScaleRecreate: Boolean = false
    protected var restoredState: Bundle? = null
        private set

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiDensity.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (shouldApplyThemePreset()) {
            ThemePresets.applyTo(this)
        }
        restoredState = sanitizeSavedInstanceState(savedInstanceState)
        super.onCreate(restoredState)
        createdUiScaleFactor = UiScale.factor(this)
    }

    override fun onResume() {
        super.onResume()
        maybeRecreateOnUiScaleChanged()
    }

    fun reapplyWindowDisplayPolicy() {
        WindowDisplayPolicy.reapply(this)
    }

    protected open fun shouldRecreateOnUiScaleChange(): Boolean = true

    protected open fun shouldApplyThemePreset(): Boolean = true

    private fun sanitizeSavedInstanceState(savedInstanceState: Bundle?): Bundle? {
        val state = savedInstanceState ?: return null
        state.classLoader = javaClass.classLoader
        return try {
            // Force unparcel up front so a bad saved state degrades to cold start instead of
            // crashing inside Activity/Fragment restoration.
            state.keySet()
            state
        } catch (t: Throwable) {
            if (!isBadSavedStateThrowable(t)) throw t
            AppLog.w("SavedState", "drop corrupted savedInstanceState for ${javaClass.simpleName}", t)
            null
        }
    }

    private fun isBadSavedStateThrowable(t: Throwable): Boolean {
        var current: Throwable? = t
        while (current != null) {
            when (current) {
                is BadParcelableException,
                is ClassNotFoundException,
                -> return true
            }
            if (current.message?.contains("ClassNotFoundException when unmarshalling") == true) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun maybeRecreateOnUiScaleChanged() {
        if (!shouldRecreateOnUiScaleChange()) return
        if (isFinishing || isDestroyed) return

        val created = createdUiScaleFactor ?: UiScale.factor(this).also { createdUiScaleFactor = it }
        val now = UiScale.factor(this)
        if (created == now) return

        if (pendingUiScaleRecreate) return
        pendingUiScaleRecreate = true
        createdUiScaleFactor = now

        // Post to avoid triggering recreate while subclasses are still running their own onResume logic.
        window?.decorView?.post {
            pendingUiScaleRecreate = false
            if (!shouldRecreateOnUiScaleChange()) return@post
            if (isFinishing || isDestroyed) return@post
            recreate()
        }
    }

    protected fun applyCloseTransitionNoAnim() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
