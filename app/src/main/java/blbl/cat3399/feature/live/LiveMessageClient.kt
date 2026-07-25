package blbl.cat3399.feature.live

import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.api.LiveSuperChatJsonParser
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.LiveSuperChat
import blbl.cat3399.core.net.BiliClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.brotli.dec.BrotliInputStream
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.zip.Inflater

class LiveMessageClient(
    private val roomId: Long,
    private val onDanmaku: (LiveDanmakuEvent) -> Unit,
    private val onSuperChat: (LiveSuperChat) -> Unit,
    private val onSuperChatDelete: (Set<Long>) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    data class LiveDanmakuEvent(
        val text: String,
        val color: Int,
        val eventTimeMs: Long,
        val sendTimeMs: Long?,
        val rndTimeMs: Long?,
    )

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "blbl-live-ws").apply { isDaemon = true }
    }
    private var heartbeatTask: ScheduledFuture<*>? = null
    private var authTimeoutTask: ScheduledFuture<*>? = null
    private var reconnectTask: ScheduledFuture<*>? = null

    private var ws: WebSocket? = null
    @Volatile private var seq: Int = 1
    @Volatile private var authed: Boolean = false
    @Volatile private var closed: Boolean = false
    @Volatile private var reconnectAttempt: Int = 0
    @Volatile private var hostIndex: Int = 0

    private var token: String = ""
    private var hosts: List<BiliApi.LiveDanmuHost> = emptyList()

    suspend fun connect() {
        closed = false
        reconnectAttempt = 0
        hostIndex = 0

        val info = BiliApi.liveDanmuInfo(roomId)
        val token = info.token
        val hosts = preferHosts(info.hosts)
        if (hosts.isEmpty() || token.isBlank()) {
            onStatus("弹幕连接信息为空")
            return
        }
        this.token = token
        this.hosts = hosts
        connectCurrentHost()
    }

    private fun preferHosts(hosts: List<BiliApi.LiveDanmuHost>): List<BiliApi.LiveDanmuHost> {
        if (hosts.isEmpty()) return emptyList()
        val (preferred, others) = hosts.partition { it.host == "broadcastlv.chat.bilibili.com" }
        return preferred + others
    }

    fun close() {
        closed = true
        heartbeatTask?.cancel(true)
        heartbeatTask = null
        authTimeoutTask?.cancel(true)
        authTimeoutTask = null
        reconnectTask?.cancel(true)
        reconnectTask = null
        ws?.close(1000, "bye")
        ws = null
        scheduler.shutdownNow()
    }

    private fun connectCurrentHost() {
        if (closed) return
        val host = hosts.getOrNull(hostIndex)
        if (host == null) {
            onStatus("弹幕连接节点为空")
            return
        }

        authed = false
        heartbeatTask?.cancel(true)
        heartbeatTask = null
        authTimeoutTask?.cancel(true)
        authTimeoutTask = null

        runCatching { ws?.close(1000, "reconnect") }
        ws = null

        val (scheme, port) =
            when {
                host.wssPort > 0 -> "wss" to host.wssPort
                host.wsPort > 0 -> "ws" to host.wsPort
                else -> "wss" to 443
            }

        val url =
            when {
                scheme == "wss" && port == 443 -> "$scheme://${host.host}/sub"
                scheme == "ws" && port == 80 -> "$scheme://${host.host}/sub"
                else -> "$scheme://${host.host}:$port/sub"
            }
        onStatus("连接弹幕：${host.host}:$port")

        val req =
            Request.Builder()
                .url(url)
                .header("User-Agent", BiliClient.prefs.userAgent)
                .header("Referer", "https://live.bilibili.com/")
                .header("Origin", "https://www.bilibili.com")
                .build()
        ws = BiliClient.apiOkHttp.newWebSocket(req, Listener())
    }

    private fun scheduleReconnect(reason: String) {
        if (closed) return
        if (hosts.isEmpty()) return
        if (reconnectTask?.isDone == false) return

        val delaySec = (1L shl reconnectAttempt.coerceIn(0, 4)).coerceAtMost(10L)
        reconnectAttempt++
        hostIndex = (hostIndex + 1).mod(hosts.size)
        val host = hosts[hostIndex]
        onStatus("弹幕重连($reconnectAttempt)：${delaySec}s 后尝试 ${host.host}")
        reconnectTask =
            scheduler.schedule(
                { connectCurrentHost() },
                delaySec,
                TimeUnit.SECONDS,
            )
        AppLog.w("LiveWs", "scheduleReconnect roomId=$roomId reason=$reason")
    }

    private fun startHeartbeat() {
        if (closed) return
        heartbeatTask?.cancel(true)
        heartbeatTask = null

        heartbeatTask =
            scheduler.scheduleWithFixedDelay(
                {
                    val hb = "[object Object]".toByteArray(Charsets.UTF_8)
                    ws?.send(ByteString.of(*buildPacket(op = OP_HEARTBEAT, ver = 1, body = hb)))
                },
                30,
                30,
                TimeUnit.SECONDS,
            )
    }

    private inner class Listener(
    ) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            onStatus("弹幕已连接")
            val uid =
                if (!BiliClient.cookies.hasSessData()) {
                    0L
                } else {
                    BiliClient.cookies
                        .getCookieValue("DedeUserID")
                        ?.trim()
                        ?.toLongOrNull()
                        ?: 0L
                }
            val body =
                JSONObject()
                    .put("uid", uid)
                    .put("roomid", roomId)
                    .put("protover", 3) // allow brotli/zlib packets
                    .put("platform", "web")
                    .put("type", 2)
                    .put("key", token)
                    .toString()
                    .toByteArray(Charsets.UTF_8)
            val sent = webSocket.send(ByteString.of(*buildPacket(op = OP_AUTH, ver = 1, body = body)))
            AppLog.d("LiveWs", "send auth ok=$sent uid=$uid roomId=$roomId")

            authTimeoutTask?.cancel(true)
            authTimeoutTask =
                scheduler.schedule(
                    {
                        if (!authed && !closed) {
                            onStatus("弹幕认证超时，准备重连")
                            scheduleReconnect("auth timeout")
                            runCatching { ws?.close(1000, "auth timeout") }
                        }
                    },
                    6,
                    TimeUnit.SECONDS,
                )
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val data = bytes.toByteArray()
            runCatching { handlePacketBytes(data) }
                .onFailure { AppLog.w("LiveWs", "handle ws packet failed size=${data.size}", it) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val preview = text.replace("\n", "\\n").replace("\r", "\\r").take(160)
            AppLog.d("LiveWs", "onMessage(text) len=${text.length} preview=$preview")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            onStatus("弹幕连接失败：${t.message}")
            AppLog.w("LiveWs", "onFailure roomId=$roomId code=${response?.code}", t)
            scheduleReconnect("onFailure code=${response?.code} ${t::class.java.simpleName}:${t.message}")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            onStatus("弹幕断开：$code $reason")
            runCatching { webSocket.close(code, reason) }
            scheduleReconnect("onClosing $code $reason")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            onStatus("弹幕已断开：$code $reason")
            scheduleReconnect("onClosed $code $reason")
        }
    }

    private fun handlePacketBytes(bytes: ByteArray) {
        parsePackets(bytes).forEach { packet ->
            when (packet.op) {
                OP_MESSAGE -> {
                    val payload =
                        when (packet.ver) {
                            0, 1 -> packet.body
                            2 -> inflateZlib(packet.body)
                            3 -> inflateBrotli(packet.body)
                            else -> null
                        } ?: return@forEach

                    // For compressed packets, the payload contains nested packets.
                    if (packet.ver == 2 || packet.ver == 3) {
                        handlePacketBytes(payload)
                    } else {
                        handleJsonMessage(payload)
                    }
                }
                OP_AUTH_REPLY -> {
                    val text = packet.body.toString(Charsets.UTF_8).trim()
                    val code = runCatching { JSONObject(text).optInt("code", -1) }.getOrDefault(-1)
                    if (code == 0) {
                        authed = true
                        reconnectAttempt = 0
                        onStatus("弹幕认证成功")
                        startHeartbeat()
                    } else {
                        onStatus("弹幕认证失败：$text")
                        scheduleReconnect("auth failed $text")
                        runCatching { ws?.close(1000, "auth failed") }
                    }
                }
                else -> Unit
            }
        }
    }

    private fun handleJsonMessage(body: ByteArray) {
        val text = body.toString(Charsets.UTF_8).trim()
        if (text.isBlank()) return
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
        val cmd = obj.optString("cmd", "")
        if (cmd.startsWith("DANMU_MSG")) {
            val info = obj.optJSONArray("info") ?: return
            val msg = info.optString(1, "").takeIf { it.isNotBlank() } ?: return
            val color =
                runCatching {
                    val extra = info.optJSONArray(0) ?: return@runCatching 0xFFFFFF
                    extra.optInt(3, 0xFFFFFF)
                }.getOrDefault(0xFFFFFF)
            val rndTimeMs =
                runCatching {
                    val extra = info.optJSONArray(0) ?: return@runCatching null
                    extra.optLong(4, 0L).takeIf { it > 0L }
                }.getOrNull()
            val sendTimeMs = obj.optLong("send_time", 0L).takeIf { it > 0L }
            val eventTimeMs = rndTimeMs ?: sendTimeMs ?: System.currentTimeMillis()
            AppLog.i(
                "LiveDanmaku",
                "ws room=$roomId event=$eventTimeMs send=$sendTimeMs rnd=$rndTimeMs color=$color textLen=${msg.length}",
            )
            onDanmaku(
                LiveDanmakuEvent(
                    text = msg,
                    color = color,
                    eventTimeMs = eventTimeMs,
                    sendTimeMs = sendTimeMs,
                    rndTimeMs = rndTimeMs,
                ),
            )
            return
        }
        when (cmd.substringBefore(':')) {
            "SUPER_CHAT_MESSAGE",
            "SUPER_CHAT_MESSAGE_JPN" -> {
                val data = obj.optJSONObject("data") ?: run {
                    AppLog.w("LiveSuperChat", "missing data room=$roomId cmd=$cmd")
                    return
                }
                val item = LiveSuperChatJsonParser.parse(data)
                if (item == null) {
                    AppLog.w("LiveSuperChat", "invalid payload room=$roomId cmd=$cmd")
                    return
                }
                onSuperChat(item)
            }

            "SUPER_CHAT_MESSAGE_DELETE" -> {
                val data = obj.optJSONObject("data") ?: return
                val ids = LiveSuperChatJsonParser.parseDeleteIds(data)
                if (ids.isNotEmpty()) onSuperChatDelete(ids)
            }
        }
    }

    private data class Packet(
        val ver: Int,
        val op: Int,
        val body: ByteArray,
    )

    private fun parsePackets(bytes: ByteArray): List<Packet> {
        val out = ArrayList<Packet>(4)
        var off = 0
        while (off + 16 <= bytes.size) {
            val packetLen = readInt(bytes, off)
            if (packetLen <= 0 || off + packetLen > bytes.size) break
            val headerLen = readShort(bytes, off + 4)
            val ver = readShort(bytes, off + 6)
            val op = readInt(bytes, off + 8)
            val bodyOff = off + headerLen
            val bodyLen = (packetLen - headerLen).coerceAtLeast(0)
            val body = if (bodyLen > 0 && bodyOff + bodyLen <= bytes.size) bytes.copyOfRange(bodyOff, bodyOff + bodyLen) else ByteArray(0)
            out.add(Packet(ver = ver, op = op, body = body))
            off += packetLen
        }
        return out
    }

    private fun buildPacket(op: Int, ver: Int, body: ByteArray): ByteArray {
        val total = 16 + body.size
        val buf = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(total)
        buf.putShort(16)
        buf.putShort(ver.toShort())
        buf.putInt(op)
        buf.putInt(seq++)
        buf.put(body)
        return buf.array()
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int {
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int
    }

    private fun readShort(bytes: ByteArray, offset: Int): Int {
        return ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
    }

    private fun inflateZlib(bytes: ByteArray): ByteArray? {
        if (bytes.isEmpty()) return ByteArray(0)
        val inflater = Inflater()
        inflater.setInput(bytes)
        val out = ByteArrayOutputStream(bytes.size * 2)
        val buf = ByteArray(8 * 1024)
        return try {
            while (!inflater.finished() && !inflater.needsInput()) {
                val n = inflater.inflate(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } catch (t: Throwable) {
            null
        } finally {
            inflater.end()
        }
    }

    private fun inflateBrotli(bytes: ByteArray): ByteArray? {
        if (bytes.isEmpty()) return ByteArray(0)
        return runCatching {
            BrotliInputStream(ByteArrayInputStream(bytes)).use { input ->
                val out = ByteArrayOutputStream(bytes.size * 2)
                val buf = ByteArray(8 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        }.getOrNull()
    }

    companion object {
        private const val OP_HEARTBEAT = 2
        private const val OP_MESSAGE = 5
        private const val OP_AUTH = 7
        private const val OP_AUTH_REPLY = 8
    }
}
