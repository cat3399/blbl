package blbl.cat3399.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import blbl.cat3399.R
import blbl.cat3399.core.ui.BaseActivity

open class SplashActivity : BaseActivity() {
    private var forwarded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
    }

    override fun onResume() {
        super.onResume()
        forwardToMainIfNeeded()
    }

    override fun shouldRecreateOnUiScaleChange(): Boolean = false

    override fun shouldApplyThemePreset(): Boolean = true

    private fun forwardToMainIfNeeded() {
        if (forwarded) return
        forwarded = true
        window?.decorView?.post {
            if (isFinishing || isDestroyed) return@post
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
            finish()
            suppressTransitionAnimation()
        }
    }

    private fun suppressTransitionAnimation() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
