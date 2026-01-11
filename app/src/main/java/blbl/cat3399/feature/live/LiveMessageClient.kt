package blbl.cat3399.feature.live

import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.log.AppLog
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
    private val onSuperChat: (LiveSuperChatEvent) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    data class LiveDanmakuEvent(
        val text: String,
        val color: Int,
    )

    data class LiveSuperChatEvent(
        val user: String,
        val message: String,
        val price: Long,
    )

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "blbl-live-ws").apply { isDaemon = true }
    }
    private var heartbeatTask: ScheduledFuture<*>? = null

    private var ws: WebSocket? = null
    @Volatile private var seq: Int = 1

    suspend fun connect() {
        val info = BiliApi.liveDanmuInfo(roomId)
        val host = info.hosts.firstOrNull()
        if (host == null || info.token.isBlank()) {
            onStatus("弹幕连接信息为空")
            return
        }
        val url = "wss://${host.host}:${host.wssPort}/sub"
        onStatus("连接弹幕：${host.host}:${host.wssPort}")

        val req =
            Request.Builder()
                .url(url)
                .header("User-Agent", BiliClient.prefs.userAgent)
                .header("Referer", "https://live.bilibili.com/")
                .build()
        ws = BiliClient.apiOkHttp.newWebSocket(req, Listener(info.token))
    }

    fun close() {
        heartbeatTask?.cancel(true)
        heartbeatTask = null
        ws?.close(1000, "bye")
        ws = null
        scheduler.shutdownNow()
    }

    private inner class Listener(
        private val token: String,
    ) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            onStatus("弹幕已连接")
            val body =
                JSONObject()
                    .put("uid", 0)
                    .put("roomid", roomId)
                    .put("protover", 2) // prefer zlib packets
                    .put("platform", "web")
                    .put("type", 2)
                    .put("key", token)
                    .toString()
                    .toByteArray(Charsets.UTF_8)
            webSocket.send(ByteString.of(*buildPacket(op = OP_AUTH, ver = 1, body = body)))

            heartbeatTask?.cancel(true)
            heartbeatTask =
                scheduler.scheduleAtFixedRate(
                    {
                        val hb = "[object Object]".toByteArray(Charsets.UTF_8)
                        ws?.send(ByteString.of(*buildPacket(op = OP_HEARTBEAT, ver = 1, body = hb)))
                    },
                    0,
                    30,
                    TimeUnit.SECONDS,
                )
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val data = bytes.toByteArray()
            runCatching { handlePacketBytes(data) }
                .onFailure { AppLog.w("LiveWs", "handle ws packet failed size=${data.size}", it) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            onStatus("弹幕连接失败：${t.message}")
            AppLog.w("LiveWs", "onFailure roomId=$roomId code=${response?.code}", t)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            onStatus("弹幕断开：$code $reason")
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
                OP_AUTH_REPLY -> onStatus("弹幕认证成功")
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
            onDanmaku(LiveDanmakuEvent(text = msg, color = color))
            return
        }
        if (cmd == "SUPER_CHAT_MESSAGE") {
            val data = obj.optJSONObject("data") ?: return
            val user = data.optJSONObject("user_info")?.optString("uname", "").orEmpty()
            val msg = data.optString("message", "")
            val price = data.optLong("price", 0L)
            if (msg.isNotBlank()) onSuperChat(LiveSuperChatEvent(user = user, message = msg, price = price))
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

