package blbl.cat3399.feature.login

import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import androidx.lifecycle.lifecycleScope
import blbl.cat3399.R
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.net.WebCookieMaintainer
import blbl.cat3399.core.ui.BaseActivity
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.core.ui.cloneInUserScale
import blbl.cat3399.core.ui.popup.AppPopup
import blbl.cat3399.databinding.ActivityQrLoginBinding
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

class QrLoginActivity : BaseActivity() {
    private lateinit var binding: ActivityQrLoginBinding
    private var flowJob: Job? = null
    private var pollJob: Job? = null
    private val passportHeaders =
        mapOf(
            "Accept" to "application/json, text/plain, */*",
            "Referer" to "https://www.bilibili.com/",
            "Origin" to "https://www.bilibili.com",
        )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrLoginBinding.inflate(layoutInflater.cloneInUserScale(this))
        setContentView(binding.root)
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)

        binding.btnRefresh.setOnClickListener { startFlow() }
        binding.btnClear.setOnClickListener {
            AppPopup.confirm(
                context = this,
                title = getString(R.string.clear_login_title),
                message = getString(R.string.clear_login_message),
                positiveText = getString(R.string.action_clear_all),
                negativeText = getString(R.string.action_cancel),
                onPositive = {
                    BiliClient.accounts.clearAllAccountsAndCurrentSession(
                        appPrefs = BiliClient.prefs,
                        cookies = BiliClient.cookies,
                    )
                    binding.tvStatus.text = "已清除登录状态"
                    binding.tvDebug.text = "login cleared"
                },
            )
        }

        binding.tvStatus.text = "正在获取二维码…"
        binding.tvDebug.text = ""
        binding.ivQr.post { startFlow() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && currentFocus == null && isNavKey(event.keyCode)) {
            binding.btnRefresh.post { binding.btnRefresh.requestFocus() }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        if (currentFocus == null) {
            binding.btnRefresh.post { binding.btnRefresh.requestFocus() }
        }
    }

    override fun onDestroy() {
        flowJob?.cancel()
        pollJob?.cancel()
        super.onDestroy()
    }

    private fun startFlow() {
        flowJob?.cancel()
        pollJob?.cancel()
        binding.tvStatus.text = "正在获取二维码…"
        flowJob = lifecycleScope.launch {
            try {
                WebCookieMaintainer.ensureWebFingerprintCookies()
                val genRaw =
                    BiliClient.requestString(
                        BiliClient.withQuery(
                            "https://passport.bilibili.com/x/passport-login/web/qrcode/generate",
                            mapOf("source" to "main_web"),
                        ),
                        headers = passportHeaders,
                    )
                val gen = withContext(Dispatchers.Default) { JSONObject(genRaw) }
                val data = gen.optJSONObject("data") ?: JSONObject()
                val url = data.optString("url", "")
                val qrcodeKey = data.optString("qrcode_key", "").trim()
                if (gen.optInt("code", -1) != 0 || url.isBlank() || qrcodeKey.isBlank()) {
                    throw IllegalStateException("二维码接口返回无效数据")
                }
                AppLog.i("QrLogin", "web generate ok key=${qrcodeKey.take(6)}")
                val qrSizePx = targetQrSizePx()
                binding.tvDebug.text = "key=${qrcodeKey.take(6)} size=$qrSizePx"
                val bmp = withContext(Dispatchers.Default) { makeQr(url, qrSizePx) }
                binding.ivQr.setImageBitmap(bmp)
                binding.tvStatus.text = "请使用哔哩哔哩 App 扫码并确认登录"
                pollJob = poll(qrcodeKey)
            } catch (t: Throwable) {
                AppLog.e("QrLogin", "generate failed", t)
                binding.tvStatus.text = "获取二维码失败：${t.message?.takeIf { it.isNotBlank() } ?: "未知错误"}"
                binding.tvDebug.text = "gen err=${t.javaClass.simpleName}"
            }
        }
    }

    private fun targetQrSizePx(): Int {
        val viewSize = min(binding.ivQr.width, binding.ivQr.height)
        val paddingX = binding.ivQr.paddingLeft + binding.ivQr.paddingRight
        val paddingY = binding.ivQr.paddingTop + binding.ivQr.paddingBottom
        val padding = max(paddingX, paddingY)
        val computed = (viewSize - padding).coerceAtLeast(1)
        return if (viewSize > 0) computed.coerceIn(720, 1200) else 900
    }

    private fun poll(key: String): Job {
        return lifecycleScope.launch {
            val pollBase = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll"
            var lastCode = Int.MIN_VALUE
            while (isActive) {
                try {
                    val pollUrl =
                        BiliClient.withQuery(
                            pollBase,
                            mapOf("qrcode_key" to key, "source" to "main_web"),
                        )
                    val raw = BiliClient.requestString(pollUrl, headers = passportHeaders)
                    val json = withContext(Dispatchers.Default) { JSONObject(raw) }
                    val data = json.optJSONObject("data") ?: JSONObject()
                    val code = data.optInt("code", json.optInt("code", -1))
                    val msg = data.optString("message", json.optString("message", json.optString("msg", "")))
                    if (code != lastCode) {
                        binding.tvDebug.text = "code=$code ${msg.trim()}"
                        lastCode = code
                    }
                    when (code) {
                        86101 -> binding.tvStatus.text = "等待扫码…"
                        86090 -> binding.tvStatus.text = "已扫码，请在手机端确认"
                        86039 -> binding.tvStatus.text = "等待扫码…"
                        86038 -> {
                            binding.tvStatus.text = "二维码已失效，请刷新"
                            return@launch
                        }

                        0 -> {
                            val refreshToken = data.optString("refresh_token", "").trim()
                            BiliClient.prefs.webRefreshToken = refreshToken.takeIf { it.isNotBlank() }
                            // 网页二维码只返回网页凭据；清除旧的 TV/App 会话，避免继续使用旧账号令牌。
                            BiliClient.prefs.appAuthSession = null
                            AppLog.i("QrLogin", "web login ok refresh=${refreshToken.take(6)} sess=${BiliClient.cookies.hasSessData()}")
                            WebCookieMaintainer.ensureBuvidActiveOncePerDay()
                            BiliClient.accounts.saveCurrentSessionAsActive(
                                appPrefs = BiliClient.prefs,
                                cookies = BiliClient.cookies,
                            )
                            binding.tvStatus.text = "登录成功，Cookie 已写入，正在返回"
                            AppLog.i("QrLogin", "login success sess=${BiliClient.cookies.hasSessData()}")
                            binding.tvDebug.text = "login ok sess=${BiliClient.cookies.hasSessData()}"
                            delay(800)
                            finish()
                            return@launch
                        }

                        else -> binding.tvStatus.text = "状态：$code $msg"
                    }
                } catch (t: Throwable) {
                    AppLog.w("QrLogin", "poll failed", t)
                    binding.tvStatus.text = "轮询失败：${t.message?.takeIf { it.isNotBlank() } ?: "未知错误"}"
                    binding.tvDebug.text = "poll err=${t.javaClass.simpleName}"
                }
                delay(2000)
            }
        }
    }

    private fun makeQr(text: String, size: Int): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 0)
        val matrix: BitMatrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            val offset = y * size
            for (x in 0 until size) {
                pixels[offset + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, size, 0, 0, size, size)
        return bmp
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
}
