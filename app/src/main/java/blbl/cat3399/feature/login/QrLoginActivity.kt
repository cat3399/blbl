package blbl.cat3399.feature.login

import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.net.AppSigner
import blbl.cat3399.core.net.WebCookieMaintainer
import blbl.cat3399.core.ui.Immersive
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

class QrLoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityQrLoginBinding
    private var pollJob: Job? = null
    private val passportHeaders =
        mapOf(
            "Referer" to "https://passport.bilibili.com/",
            "Origin" to "https://passport.bilibili.com",
        )
    private fun tvHeaders(): Map<String, String> {
        return buildMap {
            putAll(passportHeaders)
            put("buvid", BiliClient.prefs.deviceBuvid)
            put("env", "prod")
            put("app-key", "android_hd")
            put("x-bili-trace-id", "11111111111111111111111111111111:1111111111111111:0:0")
            put("x-bili-aurora-eid", "")
            put("x-bili-aurora-zone", "")
            put("bili-http-engine", "cronet")
            put("content-type", "application/x-www-form-urlencoded; charset=utf-8")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)

        binding.btnRefresh.setOnClickListener { startFlow() }
        binding.btnClear.setOnClickListener {
            BiliClient.cookies.clearAll()
            binding.tvStatus.text = "已清除 Cookie（SESSDATA 等）"
            binding.tvDebug.text = "cookie cleared"
        }

        binding.tvStatus.text = "正在申请二维码..."
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
        pollJob?.cancel()
        super.onDestroy()
    }

    private fun startFlow() {
        pollJob?.cancel()
        binding.tvStatus.text = "正在申请二维码..."
        lifecycleScope.launch {
            try {
                WebCookieMaintainer.ensureWebFingerprintCookies()
                val genParams =
                    AppSigner.signQuery(
                        mapOf(
                            "local_id" to "0",
                            "platform" to "android",
                            "mobi_app" to "android_hd",
                        ),
                    )
                val genUrl =
                    BiliClient.withQuery(
                        "https://passport.bilibili.com/x/passport-tv-login/qrcode/auth_code",
                        genParams,
                    )
                val genRaw =
                    BiliClient.requestString(
                        genUrl,
                        method = "POST",
                        headers = tvHeaders(),
                    )
                val gen = withContext(Dispatchers.Default) { JSONObject(genRaw) }
                val data = gen.optJSONObject("data") ?: JSONObject()
                val url = data.optString("url", "")
                val authCode = data.optString("auth_code", "").trim()
                AppLog.i("QrLogin", "tv generate ok auth=${authCode.take(6)}")
                val qrSizePx = targetQrSizePx()
                binding.tvDebug.text = "auth=${authCode.take(6)} size=$qrSizePx"
                val bmp = withContext(Dispatchers.Default) { makeQr(url, qrSizePx) }
                binding.ivQr.setImageBitmap(bmp)
                binding.tvStatus.text = "请使用哔哩哔哩 App 扫码并确认登录"
                pollJob = poll(authCode)
            } catch (t: Throwable) {
                AppLog.e("QrLogin", "generate failed", t)
                binding.tvStatus.text = "申请二维码失败：${t.message}"
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
            val pollBase = "https://passport.bilibili.com/x/passport-tv-login/qrcode/poll"
            var lastCode = Int.MIN_VALUE
            while (isActive) {
                try {
                    val pollParams =
                        AppSigner.signQuery(
                            mapOf(
                                "auth_code" to key,
                                "local_id" to "0",
                            ),
                        )
                    val pollUrl = BiliClient.withQuery(pollBase, pollParams)
                    val raw = BiliClient.requestString(pollUrl, method = "POST", headers = tvHeaders())
                    val json = withContext(Dispatchers.Default) { JSONObject(raw) }
                    val data = json.optJSONObject("data") ?: JSONObject()
                    val code = json.optInt("code", -1)
                    val msg = json.optString("message", json.optString("msg", ""))
                    if (code != lastCode) {
                        binding.tvDebug.text = "code=$code ${msg.trim()}"
                        lastCode = code
                    }
                    when (code) {
                        86101 -> binding.tvStatus.text = "等待扫码..."
                        86090 -> binding.tvStatus.text = "已扫码，请在手机端确认"
                        86039 -> binding.tvStatus.text = "已扫码，等待确认..."
                        86038 -> {
                            binding.tvStatus.text = "二维码已失效，请刷新"
                            return@launch
                        }

                        0 -> {
                            val tokenInfo = data.optJSONObject("token_info") ?: data
                            val accessToken = tokenInfo.optString("access_token", "").trim()
                            val refreshToken = tokenInfo.optString("refresh_token", "").trim()
                            AppLog.i("QrLogin", "tv login ok access=${accessToken.take(6)} refresh=${refreshToken.take(6)}")
                            // PiliPlus-style login does not rely on web cookie refresh_token.
                            BiliClient.prefs.webRefreshToken = null

                            val cookieInfo = data.optJSONObject("cookie_info") ?: JSONObject()
                            val cookies = cookieInfo.optJSONArray("cookies")
                            if (cookies != null) {
                                val nowMs = System.currentTimeMillis()
                                val out = ArrayList<okhttp3.Cookie>(cookies.length())
                                for (i in 0 until cookies.length()) {
                                    val c = cookies.optJSONObject(i) ?: continue
                                    val name = c.optString("name", "").trim()
                                    val value = c.optString("value", "").trim()
                                    if (name.isBlank() || value.isBlank()) continue
                                    val expiresSec = c.optLong("expires", 0L)
                                    val expiresAt = if (expiresSec > 0L) expiresSec * 1000L else (nowMs + 180L * 24 * 60 * 60 * 1000)
                                    val builder = okhttp3.Cookie.Builder()
                                        .name(name)
                                        .value(value)
                                        .domain("bilibili.com")
                                        .path("/")
                                        .expiresAt(expiresAt)
                                    if (c.optInt("http_only", 0) == 1) builder.httpOnly()
                                    if (c.optInt("secure", 0) == 1) builder.secure()
                                    out.add(builder.build())
                                }
                                if (out.isNotEmpty()) BiliClient.cookies.upsertAll(out)
                            }
                            WebCookieMaintainer.ensureBuvidActiveOnce()
                            binding.tvStatus.text = "登录成功，Cookie 已写入（返回上一页）"
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
                    binding.tvStatus.text = "轮询失败：${t.message}"
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
