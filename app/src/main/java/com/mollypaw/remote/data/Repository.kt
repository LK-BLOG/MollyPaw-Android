package com.mollypaw.remote.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.mollypaw.remote.net.ProtoConst
import com.mollypaw.remote.net.RelayClient
import com.mollypaw.remote.service.RelayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

enum class ConnState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, ERROR }

data class ChatMessage(
    val role: String,
    val content: String,
    val toolName: String? = null,
    val toolArgs: String? = null,
    val toolResult: String? = null,
    val toolDanger: String? = null,
    val toolRunning: Boolean = false,
)

data class ConversationInfo(val id: String, val title: String, val updatedAt: Double)
data class ToolCallInfo(
    val id: String,
    val name: String,
    val args: String,
    val danger: String,
    val result: String? = null,
    val running: Boolean = true,
    val rejected: Boolean = false,
)
data class FsEntry(val name: String, val path: String, val isDir: Boolean, val size: Long)
data class DeviceInfo(val id: String, val name: String, val role: String, val revoked: Boolean)

class Repository(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(ConnState.DISCONNECTED)
    val state: StateFlow<ConnState> = _state.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _streaming = MutableStateFlow<String?>(null)
    val streaming: StateFlow<String?> = _streaming.asStateFlow()

    private val _toolCalls = MutableStateFlow<List<ToolCallInfo>>(emptyList())
    val toolCalls: StateFlow<List<ToolCallInfo>> = _toolCalls.asStateFlow()

    private val _pendingApproval = MutableStateFlow<JSONObject?>(null)
    val pendingApproval: StateFlow<JSONObject?> = _pendingApproval.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _stopping = MutableStateFlow(false)
    val stopping: StateFlow<Boolean> = _stopping.asStateFlow()

    private val _conversations = MutableStateFlow<List<ConversationInfo>>(emptyList())
    val conversations: StateFlow<List<ConversationInfo>> = _conversations.asStateFlow()

    private val _currentConversationId = MutableStateFlow<String?>(null)
    val currentConversationId: StateFlow<String?> = _currentConversationId.asStateFlow()

    private val _role = MutableStateFlow("app")
    val role: StateFlow<String> = _role.asStateFlow()

    private val _deviceName = MutableStateFlow("")
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    private val _devId = MutableStateFlow("")
    val devId: StateFlow<String> = _devId.asStateFlow()

    private val _relayUrl = MutableStateFlow("")
    val relayUrl: StateFlow<String> = _relayUrl.asStateFlow()

    private val _config = MutableStateFlow<JSONObject?>(null)
    val config: StateFlow<JSONObject?> = _config.asStateFlow()

    private val _devices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val devices: StateFlow<List<DeviceInfo>> = _devices.asStateFlow()

    private val _fsRoots = MutableStateFlow<List<FsEntry>>(emptyList())
    val fsRoots: StateFlow<List<FsEntry>> = _fsRoots.asStateFlow()

    private val _fsEntries = MutableStateFlow<List<FsEntry>>(emptyList())
    val fsEntries: StateFlow<List<FsEntry>> = _fsEntries.asStateFlow()

    private val _fsPath = MutableStateFlow("")
    val fsPath: StateFlow<String> = _fsPath.asStateFlow()

    private val _fsParent = MutableStateFlow<String?>(null)
    val fsParent: StateFlow<String?> = _fsParent.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _pcOnline = MutableStateFlow(false)
    val pcOnline: StateFlow<Boolean> = _pcOnline.asStateFlow()

    val isObserver: Boolean get() = _role.value == ProtoConst.ROLE_OBSERVER

    init {
        scope.launch {
            RelayService.stateFlow.collect { raw ->
                val mapped = mapState(raw)
                _state.value = mapped
                if (raw == "authenticated") {
                    _pcOnline.value = true
                    refreshAll()
                }
            }
        }
        scope.launch {
            RelayService.eventFlow.collect { event ->
                handleEvent(event.first, event.second)
            }
        }
        scope.launch { reloadCredentials() }
    }

    private fun mapState(raw: String): ConnState = when (raw) {
        "authenticated" -> ConnState.CONNECTED
        "connecting", "ready", "authenticating" -> ConnState.CONNECTING
        "reconnecting" -> ConnState.RECONNECTING
        "error" -> ConnState.ERROR
        else -> ConnState.DISCONNECTED
    }

    private suspend fun reloadCredentials() {
        val c = CredentialsStore.load(context)
        _role.value = c.role.ifBlank { "app" }
        _deviceName.value = c.devName
        _relayUrl.value = c.url
        _devId.value = c.devId
    }

    suspend fun pair(pairingCode: String, deviceName: String, relayUrl: String = ""): Result<Credentials> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (relayUrl.isNotBlank()) CredentialsStore.setUrl(context, relayUrl.trim())
                val saved = CredentialsStore.load(context)
                val url = saved.url.ifBlank { com.mollypaw.remote.BuildConfig.DEFAULT_RELAY_URL }
                val devId = saved.devId.ifBlank { CredentialsStore.newDeviceId() }
                val parsed = com.mollypaw.remote.crypto.Proto.parsePairingCode(pairingCode)

                val listener = object : RelayClient.Listener {
                    override fun onState(state: String, detail: String) {
                        _state.value = mapState(state)
                    }

                    override fun onPayload(method: String, params: JSONObject?, msgId: String?) {
                        handleEvent(method, params)
                    }

                    override fun onError(code: String, message: String) {
                        _error.value = code + ": " + message
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
                val result = try {
                    client.pairAndReconnect(pairingCode, deviceName)
                } catch (e: Exception) {
                    null
                } ?: run {
                    client.stop("pair failed")
                    error(
                        if (!client.keyReady)
                            "电脑没在线。先开启远程互联，再生成新的配对码。"
                        else
                            "配对码没被接受。可能已过期或被用过，请重新生成。"
                    )
                }

                val creds = Credentials(
                    url = url,
                    psk = result.optString("psk", ""),
                    token = result.optString("token", ""),
                    devId = devId,
                    devName = deviceName,
                    role = result.optString("role", "app"),
                )
                if (creds.psk.isBlank()) error("服务端没有下发主密钥，配对失败")
                CredentialsStore.save(context, creds)
                _role.value = creds.role
                _deviceName.value = creds.devName
                _relayUrl.value = creds.url
                _devId.value = creds.devId
                client.stop("hand off to service")
                RelayService.start(context)
                creds
            }
        }

    suspend fun refreshAll() = withContext(Dispatchers.IO) {
        if (RelayService.instance == null) return@withContext
        loadConversations()
        loadConfig()
        loadDevices()
        loadRoots()
    }

    suspend fun send(text: String): Boolean = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext false
        if (isObserver) {
            _error.value = "只读设备不能发消息"
            return@withContext false
        }
        val svc = RelayService.instance
        if (svc == null || !svc.isConnected()) {
            _error.value = "电脑未连接"
            return@withContext false
        }
        _error.value = null
        _streaming.value = null
        _toolCalls.value = emptyList()
        _messages.value = _messages.value + ChatMessage("user", text)
        _busy.value = true
        _stopping.value = false
        val params = JSONObject().apply {
            put("text", text)
            _currentConversationId.value?.takeIf { it.isNotBlank() }?.let { put("conversation_id", it) }
        }
        val res = svc.request("chat.send", params, 30_000)
        if (res == null) {
            _error.value = "发送失败。连接可能已断，或电脑正忙。"
            _busy.value = false
            false
        } else {
            res.optString("conversation_id").takeIf { it.isNotBlank() }?.let {
                _currentConversationId.value = it
            }
            true
        }
    }

    suspend fun stop(): Boolean = withContext(Dispatchers.IO) {
        val svc = RelayService.instance ?: return@withContext false
        _stopping.value = true
        // 兜底：停止是「发出去就不管了」的请求，PC 那边这一轮要是卡在
        // 不可中断的地方，就永远不会回 chat.delta(done)。没有兜底的话
        // 「正在停止」会一直转下去，用户只能杀掉 App。
        // 12 秒后无论如何把界面恢复（那一轮可能还在收尾，但界面不能锁死）。
        scope.launch {
            delay(12_000)
            if (_stopping.value) {
                _stopping.value = false
                _busy.value = false
                _streaming.value = null
                _error.value = "停止请求已发出，但电脑那边还在收尾。可以再试一次。"
            }
        }
        val res = svc.request("chat.stop", JSONObject(), 10_000)
        if (res == null) {
            _stopping.value = false
            _error.value = "停止失败"
        }
        res != null
    }

    suspend fun loadConversations() = withContext(Dispatchers.IO) {
        val svc = RelayService.instance ?: return@withContext
        val status = svc.request("status.get", JSONObject(), 10_000)
        val currentId = status?.optString("conversation_id")?.takeIf { it.isNotBlank() }
        val res = svc.request("conv.list", JSONObject(), 20_000) ?: return@withContext
        val arr = res.optJSONArray("conversations") ?: JSONArray()
        val list = mutableListOf<ConversationInfo>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list.add(
                ConversationInfo(
                    id = o.optString("id", ""),
                    title = o.optString("title", "").ifBlank { "未命名会话" },
                    updatedAt = o.optDouble("updated_at", 0.0),
                )
            )
        }
        _conversations.value = list
        val selected = currentId
            ?: _currentConversationId.value?.takeIf { id -> list.any { it.id == id } }
            ?: list.firstOrNull()?.id
        if (!selected.isNullOrBlank()) {
            if (selected != _currentConversationId.value || _messages.value.isEmpty()) {
                loadConversation(selected)
            }
            _currentConversationId.value = selected
        }
    }

    suspend fun switchConversation(id: String) = withContext(Dispatchers.IO) {
        loadConversation(id)
        _currentConversationId.value = id
    }

    private suspend fun loadConversation(id: String) {
        val svc = RelayService.instance ?: return
        val params = JSONObject().apply { put("id", id) }
        val res = svc.request("conv.load", params, 20_000) ?: return
        val arr = res.optJSONArray("messages") ?: JSONArray()
        val toolResults = HashMap<String, String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("role") == "tool") {
                toolResults[o.optString("tool_call_id")] = o.optString("content")
            }
        }
        val list = mutableListOf<ChatMessage>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            when (o.optString("role")) {
                "user" -> list.add(ChatMessage("user", o.optString("content")))
                "assistant" -> {
                    val text = o.optString("content")
                    if (text.isNotBlank()) list.add(ChatMessage("assistant", text))
                    val calls = o.optJSONArray("tool_calls") ?: continue
                    for (j in 0 until calls.length()) {
                        val call = calls.optJSONObject(j) ?: continue
                        val id = call.optString("id")
                        val fn = call.optJSONObject("function")
                        val name = fn?.optString("name") ?: call.optString("name")
                        val args = fn?.optString("arguments") ?: call.optString("arguments")
                        list.add(
                            ChatMessage(
                                role = "tool",
                                content = "",
                                toolName = name,
                                toolArgs = args,
                                toolResult = toolResults[id],
                                toolDanger = null,
                                toolRunning = false,
                            )
                        )
                    }
                }
            }
        }
        _messages.value = list
    }

    suspend fun newConversation() = withContext(Dispatchers.IO) {
        val svc = RelayService.instance ?: return@withContext
        val res = svc.request("conv.new", JSONObject(), 20_000) ?: return@withContext
        val id = res.optString("id")
        _currentConversationId.value = id
        _messages.value = emptyList()
        _toolCalls.value = emptyList()
        loadConversations()
    }

    suspend fun deleteConversation(id: String) = withContext(Dispatchers.IO) {
        val svc = RelayService.instance ?: return@withContext
        val params = JSONObject().apply { put("id", id) }
        svc.request("conv.delete", params, 20_000)
        if (_currentConversationId.value == id) {
            _currentConversationId.value = null
            _messages.value = emptyList()
            _toolCalls.value = emptyList()
        }
        loadConversations()
    }

    suspend fun loadConfig() = withContext(Dispatchers.IO) {
        RelayService.instance?.request("config.get", JSONObject(), 20_000)?.let {
            _config.value = it
        }
    }

    suspend fun saveConfig(patch: JSONObject) = withContext(Dispatchers.IO) {
        val res = RelayService.instance?.request("config.save", patch, 20_000)
        if (res == null) {
            _error.value = "保存设置失败"
        } else {
            _notice.value = "设置已保存"
            loadConfig()
        }
        res != null
    }

    suspend fun loadDevices() = withContext(Dispatchers.IO) {
        val res = RelayService.instance?.request("device.list", JSONObject(), 20_000)
            ?: return@withContext
        val arr = res.optJSONArray("devices") ?: JSONArray()
        val list = mutableListOf<DeviceInfo>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list.add(
                DeviceInfo(
                    id = o.optString("dev_id"),
                    name = o.optString("name"),
                    role = o.optString("role"),
                    revoked = o.optBoolean("revoked", false),
                )
            )
        }
        _devices.value = list
    }

    suspend fun revokeDevice(devId: String) = withContext(Dispatchers.IO) {
        val params = JSONObject().apply { put("dev_id", devId) }
        val res = RelayService.instance?.request("device.revoke", params, 20_000)
        if (res == null) _error.value = "吊销设备失败" else loadDevices()
    }

    suspend fun loadRoots() = withContext(Dispatchers.IO) {
        val res = RelayService.instance?.request("fs.roots", JSONObject(), 20_000)
            ?: return@withContext
        val arr = res.optJSONArray("roots") ?: JSONArray()
        val list = mutableListOf<FsEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val path = o.optString("path")
            list.add(FsEntry(o.optString("name", path), path, true, 0))
        }
        _fsRoots.value = list
    }

    suspend fun listFiles(path: String) = withContext(Dispatchers.IO) {
        val params = JSONObject().apply { put("path", path) }
        val res = RelayService.instance?.request("fs.list", params, 20_000)
            ?: return@withContext
        val actualPath = res.optString("path", path)
        val parent = res.optString("parent").takeIf { it.isNotBlank() }
        val arr = res.optJSONArray("entries") ?: JSONArray()
        val list = mutableListOf<FsEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name")
            list.add(FsEntry(name, joinPath(actualPath, name), o.optBoolean("is_dir"), o.optLong("size")))
        }
        _fsPath.value = actualPath
        _fsParent.value = parent
        _fsEntries.value = list
    }

    suspend fun refreshFiles() = withContext(Dispatchers.IO) {
        val current = _fsPath.value
        if (current.isBlank()) loadRoots() else listFiles(current)
    }

    private fun joinPath(base: String, name: String): String {
        if (base.endsWith("\\") || base.endsWith("/")) return base + name
        return base + "/" + name
    }

    suspend fun downloadFile(path: String, name: String): String? = withContext(Dispatchers.IO) {
        val svc = RelayService.instance ?: run {
            _error.value = "后台服务没运行"
            return@withContext null
        }
        var offset = 0L
        var output: java.io.OutputStream? = null
        var uri: android.net.Uri? = null
        var localPath = ""
        try {
            while (true) {
                val params = JSONObject().apply {
                    put("path", path)
                    put("offset", offset)
                    put("len", 256 * 1024)
                }
                val res = svc.request("fs.read", params, 60_000) ?: throw RuntimeException("读取失败")
                val raw = Base64.getDecoder().decode(res.optString("data_b64"))
                if (output == null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, name)
                            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                            put(
                                MediaStore.Downloads.RELATIVE_PATH,
                                Environment.DIRECTORY_DOWNLOADS + "/MollyPaw"
                            )
                        }
                        uri = context.contentResolver.insert(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                        )
                        output = context.contentResolver.openOutputStream(uri!!)
                        localPath = "Download/MollyPaw/" + name
                    } else {
                        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                            ?: context.filesDir
                        val file = java.io.File(dir, name)
                        output = file.outputStream()
                        localPath = file.absolutePath
                    }
                }
                output?.write(raw)
                offset += raw.size
                if (res.optBoolean("eof") || raw.isEmpty()) break
            }
            output?.flush()
            _notice.value = "已下载到 " + localPath
            localPath
        } catch (e: Exception) {
            uri?.let { context.contentResolver.delete(it, null, null) }
            _error.value = "下载失败: " + (e.message ?: "未知错误")
            null
        } finally {
            runCatching { output?.close() }
        }
    }

    private fun handleEvent(method: String, params: JSONObject?) {
        when (method) {
            "chat.delta" -> {
                val text = params?.optString("text", "") ?: ""
                val done = params?.optBoolean("done", false) ?: false
                if (!done) {
                    if (text.isNotEmpty()) {
                        _streaming.value = (_streaming.value ?: "") + text
                    }
                } else {
                    val cancelled = params?.optBoolean("cancelled", false) ?: false
                    val err = params?.optString("error", "") ?: ""
                    val finalText = if (text.isNotEmpty()) text else (_streaming.value ?: "")
                    if (err.isNotBlank()) _error.value = err
                    if (!cancelled && finalText.isNotBlank()) {
                        _messages.value = _messages.value + ChatMessage("assistant", finalText)
                    }
                    _streaming.value = null
                    _busy.value = false
                    _stopping.value = false
                    scope.launch { loadConversations() }
                }
            }

            "chat.tool_call" -> {
                val o = params ?: return
                val id = o.optString("request_id").ifBlank { "tool-" + System.currentTimeMillis() }
                val item = ToolCallInfo(
                    id = id,
                    name = o.optString("name", "tool"),
                    args = o.opt("args")?.toString() ?: "",
                    danger = o.optString("danger_level", ""),
                    running = true,
                )
                val current = _toolCalls.value.toMutableList()
                val index = current.indexOfFirst { it.id == id }
                if (index >= 0) current[index] = item else current.add(item)
                _toolCalls.value = current
            }

            "chat.tool_result" -> {
                val o = params ?: return
                val id = o.optString("request_id")
                val current = _toolCalls.value.toMutableList()
                val index = current.indexOfFirst { it.id == id }
                if (index >= 0) {
                    current[index] = current[index].copy(
                        result = o.optString("result", ""),
                        running = false,
                        rejected = o.optBoolean("rejected", false),
                    )
                    _toolCalls.value = current
                }
            }

            "approval.request" -> _pendingApproval.value = params
            "approval.resolved" -> {
                val id = params?.optString("request_id")
                if (id.isNullOrBlank() || _pendingApproval.value?.optString("request_id") == id) {
                    _pendingApproval.value = null
                }
            }
            // 收到「这一轮被停了」的事件 —— 这是**结束**信号。
            // 以前这里写的是 _stopping.value = true，收到停止反而把
            // 「正在停止」标志置上，界面永远转圈（用户说"停止不了"）。
            "chat.stopped" -> {
                _stopping.value = false
                _busy.value = false
                _streaming.value = null
            }
            "presence" -> {
                val pc = params?.optInt("pc", 1) ?: 1
                _pcOnline.value = pc > 0
            }
            "error" -> {
                _error.value = params?.optString("msg", "未知错误")
                _busy.value = false
                _stopping.value = false
            }
        }
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

    fun clearError() { _error.value = null }
    fun clearNotice() { _notice.value = null }
}
