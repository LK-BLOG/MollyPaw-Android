package com.mollypaw.remote.data

import android.content.Context
import com.mollypaw.remote.net.ProtoConst
import com.mollypaw.remote.net.RelayClient
import com.mollypaw.remote.service.RelayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 业务仓库：把中继的方法调用包成 Android 侧好用的接口。
 *
 * 方法表见 PROTOCOL.md §6。权限由 PC 端强制（observer 会被拒），
 * 这里不做本地权限判断 —— 免得两边规则不一致。
 */
class Repository(private val context: Context) {

    /** 连接状态。 */
    enum class ConnState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, ERROR }

    private val _state = MutableStateFlow(ConnState.DISCONNECTED)
    val state: StateFlow<ConnState> = _state

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    /** 收到的增量文本（流式）。 */
    private val _streaming = MutableStateFlow<String?>(null)
    val streaming: StateFlow<String?> = _streaming

    /** 待处理的审批请求。 */
    private val _pendingApproval = MutableStateFlow<JSONObject?>(null)
    val pendingApproval: StateFlow<JSONObject?> = _pendingApproval

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    /** 会话列表。 */
    private val _conversations = MutableStateFlow<List<JSONObject>>(emptyList())
    val conversations: StateFlow<List<JSONObject>> = _conversations

    // -- 配对 --------------------------------------------------------------

    /**
     * 用配对码完成配对。
     *
     * 配对码 = MP1.<b64(pair_seed)>.<room>，手机**不需要先知道主 PSK**：
     * 服务端会在配对响应里把主 PSK 下发，我们存起来，之后就能正常通信。
     *
     * 房间号就在配对码里，所以不用先知道中继地址以外的东西。
     */
    suspend fun pair(pairingCode: String, deviceName: String): Result<Credentials> =
        withContext(Dispatchers.IO) {
            runCatching {
                val saved = CredentialsStore.load(context)
                val url = saved.url.ifBlank {
                    com.mollypaw.remote.BuildConfig.DEFAULT_RELAY_URL
                }
                val devId = saved.devId.ifBlank { CredentialsStore.newDeviceId() }

                // 房间号从配对码里读出来
                val parsed = com.mollypaw.remote.crypto.Proto.parsePairingCode(pairingCode)

                val listener = object : RelayClient.Listener {
                    override fun onState(state: String, detail: String) {
                        _state.value = when (state) {
                            "connected", "ready" -> ConnState.CONNECTED
                            "connecting" -> ConnState.CONNECTING
                            "reconnecting" -> ConnState.RECONNECTING
                            "error" -> ConnState.ERROR
                            else -> ConnState.DISCONNECTED
                        }
                    }

                    override fun onPayload(method: String, params: JSONObject?, msgId: String?) {
                        handleEvent(method, params)
                    }

                    override fun onError(code: String, message: String) {
                        _error.value = "$code: $message"
                    }

                    override fun onPresence(pc: Int, app: Int, observer: Int) {}
                }

                val client = RelayClient(
                    url = url,
                    room = parsed.room,
                    role = ProtoConst.ROLE_APP,
                    devId = devId,
                    listener = listener,
                )
                val result = client.pairAndReconnect(pairingCode, deviceName)
                    ?: error("配对失败：没收到响应")

                val creds = Credentials(
                    url = url,
                    psk = result.optString("psk", ""),
                    token = result.optString("token", ""),
                    devId = devId,
                    devName = deviceName,
                    role = result.optString("role", "app"),
                )
                if (creds.psk.isBlank()) {
                    error("服务端没有下发主密钥，配对无法完成")
                } else {
                    CredentialsStore.save(context, creds)
                    // 配对完成后启动常驻服务
                    RelayService.start(context)
                    creds
                }
            }
        }

    // -- 业务调用 ----------------------------------------------------------

    suspend fun send(text: String): Boolean = withContext(Dispatchers.IO) {
        val svc = RelayService.instance
        if (svc == null) {
            _error.value = "后台服务没在运行"
            return@withContext false
        }
        _busy.value = true
        val params = JSONObject().apply { put("text", text) }
        val res = svc.request("chat.send", params, 30_000)
        if (res == null) {
            _error.value = "发送失败（电脑可能不在线）"
        }
        _busy.value = false
        res != null
    }

    suspend fun stop(): Boolean = withContext(Dispatchers.IO) {
        val svc = RelayService.instance ?: return@withContext false
        svc.request("chat.stop", JSONObject(), 10_000) != null
    }

    suspend fun loadConversations() = withContext(Dispatchers.IO) {
        val svc = RelayService.instance ?: return@withContext
        val res = svc.request("conv.list", JSONObject(), 20_000) ?: return@withContext
        val arr = res.optJSONArray("conversations") ?: return@withContext
        val list = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
        _conversations.value = list
    }

    suspend fun newConversation() = withContext(Dispatchers.IO) {
        val svc = RelayService.instance ?: return@withContext
        val res = svc.request("conv.new", JSONObject(), 20_000) ?: return@withContext
        loadConversations()
    }

    suspend fun loadConversation(id: String) = withContext(Dispatchers.IO) {
        val svc = RelayService.instance ?: return@withContext
        val params = JSONObject().apply { put("id", id) }
        val res = svc.request("conv.load", params, 20_000) ?: return@withContext
        val arr = res.optJSONArray("messages") ?: return@withContext
        val list = mutableListOf<ChatMessage>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                ChatMessage(
                    role = o.optString("role", "user"),
                    content = o.optString("content", ""),
                )
            )
        }
        _messages.value = list
    }

    suspend fun deleteConversation(id: String) = withContext(Dispatchers.IO) {
        val svc = RelayService.instance ?: return@withContext
        val params = JSONObject().apply { put("id", id) }
        svc.request("conv.delete", params, 20_000)
        loadConversations()
    }

    suspend fun replyApproval(requestId: String, approved: Boolean) =
        withContext(Dispatchers.IO) {
            val svc = RelayService.instance ?: return@withContext
            val params = JSONObject().apply {
                put("req_id", requestId)
                put("approved", approved)
            }
            svc.request("approval.reply", params, 20_000)
            _pendingApproval.value = null
        }

    suspend fun getConfig(): JSONObject? = withContext(Dispatchers.IO) {
        RelayService.instance?.request("config.get", JSONObject(), 20_000)
    }

    suspend fun listDevices(): JSONObject? = withContext(Dispatchers.IO) {
        RelayService.instance?.request("device.list", JSONObject(), 20_000)
    }

    // -- 事件处理 ----------------------------------------------------------

    private fun handleEvent(method: String, params: JSONObject?) {
        when (method) {
            "chat.delta" -> {
                val text = params?.optString("text", "") ?: ""
                val done = params?.optBoolean("done", false) ?: false
                if (text.isNotEmpty()) {
                    _streaming.value = (_streaming.value ?: "") + text
                }
                if (done) {
                    val full = _streaming.value ?: ""
                    _streaming.value = null
                    _messages.value = _messages.value + ChatMessage("assistant", full)
                    _busy.value = false
                }
            }

            "approval.request" -> {
                _pendingApproval.value = params
            }

            "tool.call" -> {
                // 工具调用提示（可展开查看）
            }

            "error" -> {
                _error.value = params?.optString("msg", "") ?: "未知错误"
                _busy.value = false
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}

data class ChatMessage(val role: String, val content: String)
