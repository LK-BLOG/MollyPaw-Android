package com.mollypaw.remote.net

import com.mollypaw.remote.crypto.Envelope
import com.mollypaw.remote.crypto.Hkdf
import com.mollypaw.remote.crypto.Proto
import com.mollypaw.remote.crypto.X25519
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 中继 WebSocket 客户端 —— 与 Python 端 agent/relay/client.py 行为对齐。
 *
 * 职责：
 *   - 拨号到中继，发 hello 交换 eph/hs
 *   - 派生会话密钥，加解密 data 帧（AAD 绑定 room/role/seq）
 *   - 心跳、断线指数退避重连
 *   - 把收到的业务载荷交给回调
 *
 * 它**不懂业务语义**（不认 chat / config / fs），那些在 Repository 层。
 */
class RelayClient(
    private val url: String,
    private val room: String,
    private val role: String,
    private val devId: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onState(state: String, detail: String = "")
        fun onPayload(method: String, params: JSONObject?, msgId: String?)
        fun onError(code: String, message: String)
        fun onPresence(pc: Int, app: Int, observer: Int)
    }

    /** 当前用于派生会话密钥的密钥材料（PSK，或配对期间的 pair_key）。 */
    @Volatile
    var keyMaterial: String = ""

    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)   // OkHttp 自带 WS 心跳
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // WS 长连接，读超时关掉
        .build()

    private var socket: WebSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val running = AtomicBoolean(false)
    private val seq = AtomicLong(0)

    // 握手材料
    private var ephPriv: ByteArray? = null
    private var ephPub: ByteArray? = null
    private var hs: ByteArray? = null
    private var peerEph: ByteArray? = null
    private var peerHs: ByteArray? = null

    @Volatile
    private var sessionKey: ByteArray? = null

    /** 会话密钥是否已就绪（收到对端 hello 后）。 */
    val keyReady: Boolean get() = sessionKey != null

    private var loopJob: Job? = null

    // -- 生命周期 ----------------------------------------------------------

    fun start() {
        if (!running.compareAndSet(false, true)) return
        loopJob = scope.launch { connectLoop() }
    }

    fun stop(reason: String = "user") {
        running.set(false)
        loopJob?.cancel()
        socket?.let {
            runCatching { it.send(Frame.bye(room, role, reason).toJson()) }
            runCatching { it.close(1000, reason) }
        }
        socket = null
        listener.onState("stopped")
    }

    private suspend fun connectLoop() {
        var attempt = 0
        while (running.get() && scope.isActive) {
            val ok = try {
                connectOnce()
                true
            } catch (e: Exception) {
                listener.onState("error", e.message ?: "连接失败")
                false
            }
            if (!running.get()) break

            if (ok) {
                attempt = 0
                // 等连接自己断掉（socket 关闭会把 running 之外的标志复位）
                while (running.get() && socket != null && scope.isActive) {
                    delay(500)
                }
            }

            if (!running.get()) break
            val wait = Proto.backoffSeconds(attempt)
            listener.onState("reconnecting", "第 ${attempt + 1} 次，${wait}s 后重试")
            attempt++
            // 带抖动，避免多设备同时重连打爆中继
            val jittered = (wait * (0.8 + Math.random() * 0.4) * 1000).toLong()
            delay(jittered)
        }
    }

    private suspend fun connectOnce() = withContext(Dispatchers.IO) {
        listener.onState("connecting")

        // 每次连接都用新的临时密钥对 —— 前向保密
        val kp = X25519.generate()
        ephPriv = kp.privateKey
        ephPub = kp.publicKey
        hs = X25519.randomBytes(Envelope.HS_LEN)
        peerEph = null
        peerHs = null
        sessionKey = null
        seq.set(0)

        val full = if (url.contains("?")) "$url&room=$room" else "$url?room=$room"
        val request = Request.Builder().url(full).build()
        val ws = http.newWebSocket(request, Handler())
        socket = ws
    }

    // -- 发送 --------------------------------------------------------------

    fun nextSeq(): Long = seq.incrementAndGet()

    /**
     * 加密发送一个业务载荷。
     *
     * @return true 表示已塞进发送队列；false 表示还没握手完/连接不在。
     */
    fun sendPayload(method: String, params: JSONObject?, msgId: String?): Boolean {
        val key = sessionKey ?: return false
        val id = msgId ?: java.util.UUID.randomUUID().toString()
        val s = nextSeq()
        val payload = JSONObject().apply {
            put("m", method)
            put("p", params ?: JSONObject())
            put("id", id)
        }
        val (nonce, blob) = Envelope.encrypt(
            key, room, role, s, payload.toString().toByteArray(Charsets.UTF_8)
        )
        val frame = Frame.data(room, role, s, id, Envelope.b64e(nonce), Envelope.b64e(blob))
        return socket?.send(frame.toJson()) ?: false
    }

    fun sendResponse(
        method: String, msgId: String, ok: Boolean,
        result: JSONObject? = null, error: String? = null,
    ): Boolean {
        val key = sessionKey ?: return false
        val s = nextSeq()
        val payload = JSONObject().apply {
            put("m", method)
            put("id", msgId)
            put("ok", ok)
            if (ok) put("r", result ?: JSONObject()) else put("e", error ?: "internal")
        }
        val (nonce, blob) = Envelope.encrypt(
            key, room, role, s, payload.toString().toByteArray(Charsets.UTF_8)
        )
        val frame = Frame.data(room, role, s, msgId, Envelope.b64e(nonce), Envelope.b64e(blob))
        return socket?.send(frame.toJson()) ?: false
    }

    // -- 接收 --------------------------------------------------------------

    private inner class Handler : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val pub = ephPub ?: return
            val nonce = hs ?: return
            val hello = Frame.hello(
                room, role, devId,
                Envelope.b64e(pub), Envelope.b64e(nonce),
            )
            webSocket.send(hello.toJson())
            listener.onState("connected")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = Frame.parse(text) ?: return
            handle(frame)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (socket === webSocket) socket = null
            if (running.get()) {
                listener.onState("disconnected", t.message ?: "")
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (socket === webSocket) socket = null
            if (running.get()) listener.onState("disconnected", reason)
        }
    }

    private fun handle(frame: Frame) {
        when (frame.t) {
            ProtoConst.T_ERROR -> {
                listener.onError(frame.code ?: "internal", frame.msg ?: "")
            }

            ProtoConst.T_PRESENCE -> {
                listener.onPresence(frame.pc ?: 0, frame.app ?: 0, frame.observer ?: 0)
            }

            ProtoConst.T_PING -> {
                socket?.send(Frame(t = ProtoConst.T_PONG, r = room, f = role).toJson())
            }

            ProtoConst.T_PONG -> {
                // 心跳回复，什么都不用做
            }

            ProtoConst.T_HELLO -> handleHello(frame)

            ProtoConst.T_DATA -> handleData(frame)
        }
    }

    private fun handleHello(frame: Frame) {
        // 自己发出的 hello 会被中继回放回来，跳过
        if (frame.f == role && frame.dev == devId) return
        val ephB64 = frame.eph ?: return
        val hsB64 = frame.hs ?: return

        // 设备重连时会换新的临时密钥 —— 必须重新派生。
        // 判据是 eph/hs 是否变化，不是"dev 是否见过"：
        // 手机重连后 devId 不变、eph 变了，按 dev 判重会把新 hello 丢掉。
        val newEph = Envelope.b64d(ephB64)
        if (peerEph != null && peerEph!!.contentEquals(newEph)) return

        peerEph = newEph
        peerHs = Envelope.b64d(hsB64)
        deriveSessionKey()
    }

    private fun deriveSessionKey() {
        val myPriv = ephPriv ?: return
        val theirEph = peerEph ?: return
        val myHs = hs ?: return
        val theirHs = peerHs ?: return

        // salt 顺序固定 hs_pc ‖ hs_app，与谁发送无关
        val (hsPc, hsApp) = if (role == ProtoConst.ROLE_PC) {
            myHs to theirHs
        } else {
            theirHs to myHs
        }

        sessionKey = try {
            Proto.deriveSessionKey(myPriv, theirEph, keyMaterial, hsPc, hsApp)
        } catch (e: Exception) {
            listener.onState("error", "密钥派生失败：${e.message}")
            null
        }
        if (sessionKey != null) listener.onState("ready")
    }

    private fun handleData(frame: Frame) {
        val key = sessionKey ?: return
        val nonceB64 = frame.n ?: return
        val cipherB64 = frame.c ?: return
        val sender = frame.f ?: return
        val s = frame.seq

        val payload = try {
            val plain = Envelope.decrypt(
                key, frame.r ?: room, sender, s,
                Envelope.b64d(nonceB64), Envelope.b64d(cipherB64),
            )
            JSONObject(String(plain, Charsets.UTF_8))
        } catch (e: Exception) {
            // 认证失败是硬错误：不要退回明文、不要重试
            listener.onError("bad_frame", "解密失败（seq=$s）")
            return
        }

        val method = payload.optString("m", "")
        // 响应帧带 ok 字段：先唤醒 request() 的等待者，再通知业务层
        if (payload.has("ok")) {
            val id = payload.optString("id", "")
            deliverResponse(id, payload)
            listener.onPayload("__response__", payload, id)
            return
        }
        listener.onPayload(method, payload.optJSONObject("p"), payload.optString("id", ""))
    }

    // -- 配对 --------------------------------------------------------------

    /**
     * 用配对码完成配对，并**自动用主 PSK 重连**。
     *
     * 配对码 = MP1.<b64(pair_seed)>.<room>
     * 手机不需要事先知道主 PSK：pair_seed 派生出 pair_key 与 reg_token。
     *
     * 配对成功后服务端会下发主 PSK，**必须换它重连**：
     * 服务端在配对成功的一瞬间就作废了配对密钥（配对码一次性），
     * 继续用旧连接发请求会全部超时（Python 端实测踩过这个坑）。
     *
     * @return 配对响应（含 role / token / psk），失败返回 null
     */
    suspend fun pairAndReconnect(pairingCode: String, devName: String): JSONObject? =
        withContext(Dispatchers.IO) {
            val (seed, _) = Proto.parsePairingCode(pairingCode)
            val pairKey = Proto.derivePairKey(seed)
            val regToken = Proto.deriveRegToken(seed)

            keyMaterial = Envelope.b64e(pairKey)
            start()

            var waited = 0
            while (!keyReady && waited < 10_000) {
                delay(100)
                waited += 100
            }
            if (!keyReady) {
                stop("pair handshake timeout")
                return@withContext null
            }

            val devPub = X25519.randomBytes(32)
            val params = JSONObject().apply {
                put("dev_id", devId)
                put("dev_name", devName)
                put("dev_pub", Envelope.b64e(devPub))
                put("reg_token", Envelope.b64e(regToken))
            }

            val result = request("pair.req", params, 20_000) ?: run {
                stop("pair failed")
                return@withContext null
            }

            val issued = result.optString("psk", "")
            if (issued.isEmpty()) {
                // 服务端没下发（旧版本），配对虽成功但后续没法通信
                listener.onError("pair_no_psk", "服务端没有下发主密钥，无法继续")
                stop("no psk issued")
                return@withContext null
            }

            // 换主 PSK 重连 —— 关键一步
            keyMaterial = issued
            restart()

            result
        }

    /** 断开重连（用于切换密钥后使新密钥生效）。 */
    suspend fun restart() = withContext(Dispatchers.IO) {
        socket?.let {
            runCatching { it.close(1000, "rekey") }
        }
        socket = null
        sessionKey = null
        peerEph = null
        peerHs = null

        // 重新握手
        val kp = X25519.generate()
        ephPriv = kp.privateKey
        ephPub = kp.publicKey
        hs = X25519.randomBytes(Envelope.HS_LEN)
        seq.set(0)

        val full = if (url.contains("?")) "$url&room=$room" else "$url?room=$room"
        val request = Request.Builder().url(full).build()
        socket = http.newWebSocket(request, Handler())

        var waited = 0
        while (!keyReady && waited < 10_000) {
            delay(100)
            waited += 100
        }
    }

    /**
     * 发一个请求并等响应（按 msgId 配对）。
     *
     * @return 成功返回响应载荷的 r 字段；失败返回 null
     */
    suspend fun request(method: String, params: JSONObject?, timeoutMs: Long): JSONObject? =
        withContext(Dispatchers.IO) {
            val id = java.util.UUID.randomUUID().toString()
            val latch = java.util.concurrent.CountDownLatch(1)
            val box = arrayOfNulls<JSONObject>(1)

            pending[id] = Pending(latch, box)
            if (!sendPayload(method, params, id)) {
                pending.remove(id)
                return@withContext null
            }
            val ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            pending.remove(id)
            if (!ok) return@withContext null
            val payload = box[0] ?: return@withContext null
            if (payload.optBoolean("ok", false)) payload.optJSONObject("r") else null
        }

    private class Pending(
        val latch: java.util.concurrent.CountDownLatch,
        val box: Array<JSONObject?>,
    )

    private val pending = java.util.concurrent.ConcurrentHashMap<String, Pending>()

    /** 供 handleData 在收到响应时唤醒等待者。 */
    fun deliverResponse(msgId: String, payload: JSONObject) {
        pending[msgId]?.let {
            it.box[0] = payload
            it.latch.countDown()
        }
    }
}
