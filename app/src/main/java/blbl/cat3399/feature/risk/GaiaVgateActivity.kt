package blbl.cat3399.feature.risk

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.BaseActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GaiaVgateActivity : BaseActivity() {
    private lateinit var status: TextView
    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val vVoucher = intent.getStringExtra(EXTRA_V_VOUCHER).orEmpty().trim()
        if (vVoucher.isBlank()) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        val registerContext =
            buildString {
                append("vVoucherLen=").append(vVoucher.length)
                append(" vVoucherPrefix=").append(vVoucher.take(8))
                append(" hasSess=").append(if (BiliClient.cookies.hasSessData()) 1 else 0)
                append(" hasCsrf=").append(
                    if (BiliClient.cookies.getCookieValue("bili_jct").orEmpty().trim().isNotBlank()) 1 else 0,
                )
                append(" hasGaiaVtoken=").append(
                    if (
                        BiliClient.cookies
                            .getCookieValue("x-bili-gaia-vtoken")
                            .orEmpty()
                            .trim()
                            .isNotBlank()
                    ) {
                        1
                    } else {
                        0
                    },
                )
            }

        status = TextView(this).apply { text = "正在申请风控验证…" }
        webView =
            WebView(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                webChromeClient = WebChromeClient()
            }

        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(webView)
            }
        setContentView(root)

        lifecycleScope.launch {
            try {
                AppLog.i("GaiaVgate", "register start $registerContext")
                val reg =
                    withContext(Dispatchers.IO) {
                        BiliApi.gaiaVgateRegister(vVoucher)
                    }
                AppLog.i(
                    "GaiaVgate",
                    "register ok $registerContext token=${reg.token.take(8)} gt=${reg.gt.take(6)} challenge=${reg.challenge.take(6)}",
                )
                status.text = "请完成验证…"
                val bridge = Bridge(token = reg.token)
                webView.addJavascriptInterface(bridge, "Android")
                webView.loadDataWithBaseURL(
                    "https://api.bilibili.com/",
                    geetestHtml(gt = reg.gt, challenge = reg.challenge),
                    "text/html",
                    "utf-8",
                    null,
                )
            } catch (t: Throwable) {
                AppLog.e("GaiaVgate", "register failed $registerContext", t)
                status.text = "申请失败：${t.message?.takeIf { it.isNotBlank() } ?: "未知错误"}"
            }
        }
    }

    override fun onDestroy() {
        runCatching {
            webView.removeJavascriptInterface("Android")
            webView.destroy()
        }
        super.onDestroy()
    }

    private inner class Bridge(
        private val token: String,
    ) {
        @JavascriptInterface
        fun onGeetestResult(validate: String?, seccode: String?, geetestChallenge: String?) {
            val v = validate.orEmpty().trim()
            val s = seccode.orEmpty().trim()
            val c = geetestChallenge.orEmpty().trim()
            if (v.isBlank() || s.isBlank() || c.isBlank()) return

            lifecycleScope.launch {
                try {
                    status.text = "验证成功，正在提交…"
                    val grisk =
                        withContext(Dispatchers.IO) {
                            BiliApi.gaiaVgateValidate(
                                token = token,
                                geetestChallenge = c,
                                validate = v,
                                seccode = s,
                            )
                        }
                    val out = Intent().putExtra(EXTRA_GAIA_VTOKEN, grisk)
                    setResult(RESULT_OK, out)
                    finish()
                } catch (t: Throwable) {
                    AppLog.e("GaiaVgate", "validate failed token=${token.take(8)}", t)
                    status.text = "提交失败：${t.message?.takeIf { it.isNotBlank() } ?: "未知错误"}"
                }
            }
        }
    }

    private fun geetestHtml(gt: String, challenge: String): String {
        val safeGt = gt.replace("'", "\\'")
        val safeChallenge = challenge.replace("'", "\\'")
        return """
<!DOCTYPE html>
<html>
<head>
  <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
  <script src="https://static.geetest.com/static/tools/gt.js"></script>
  <style>
    body { font-family: sans-serif; margin: 0; padding: 12px; }
    #captcha { margin-top: 12px; }
    #btnStart { margin-top: 12px; padding: 10px 14px; font-size: 16px; }
  </style>
</head>
<body>
  <div>风控验证（极验）</div>
  <button id="btnStart" disabled>开始验证</button>
  <div id="captcha"></div>
  <script>
    function start() {
      if (typeof initGeetest !== 'function') {
        document.body.innerHTML = '<div>加载验证码脚本失败</div>';
        return;
      }
      initGeetest({
        gt: '$safeGt',
        challenge: '$safeChallenge',
        new_captcha: true,
        product: 'bind',
        offline: false,
        https: true
      }, function (captchaObj) {
        captchaObj.appendTo('#captcha');
        captchaObj.onReady(function () {
          var btn = document.getElementById('btnStart');
          if (btn) btn.disabled = false;
        });
        var btn = document.getElementById('btnStart');
        if (btn) {
          btn.addEventListener('click', function () {
            captchaObj.verify();
          });
        }
        captchaObj.onSuccess(function () {
          var res = captchaObj.getValidate();
          if (!res) return;
          if (window.Android && window.Android.onGeetestResult) {
            window.Android.onGeetestResult(res.geetest_validate, res.geetest_seccode, res.geetest_challenge);
          }
        });
      });
    }
    start();
  </script>
</body>
</html>
        """.trimIndent()
    }

    companion object {
        const val EXTRA_V_VOUCHER = "v_voucher"
        const val EXTRA_GAIA_VTOKEN = "gaia_vtoken"
    }
}
