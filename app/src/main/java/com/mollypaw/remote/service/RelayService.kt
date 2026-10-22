package com.mollypaw.remote.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.mollypaw.remote.MainActivity
import com.mollypaw.remote.R
import com.mollypaw.remote.data.CredentialsStore
import com.mollypaw.remote.net.ProtoConst
import com.mollypaw.remote.net.RelayClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 常驻前台服务。
 *
 * 存在的理由：手机要能**随时**收到电脑的消息、随时点审批。
 * 没有前台服务的话，App 一退到后台就会被系统（尤其国产 ROM）杀掉，
 * 用户看到的现象是"电脑发的消息手机永远收不到"。
 *
 * 这是用户明确要求的："必须常驻后台，断了自动重连（要要前台服务 + 通知）"
 */
class RelayService : Service(), RelayClient.Listener {

    companion object {
        const val ACTION_START = "com.mollypaw.remote.START"
        const val ACTION_STOP = "com.mollypaw.remote.STOP"
        const val ACTION_RESTART = "com.mollypaw.remote.RESTART"

        const val CHANNEL_SERVICE = "mollypaw_service"
        const val CHANNEL_MESSAGE = "mollypaw_message"
        const val CHANNEL_APPROVAL = "mollypaw_approval"

        private const val NOTIF_ID_SERVICE = 1001
        private const val NOTIF_ID_MESSAGE = 1002
        private const val NOTIF_ID_APPROVAL_BASE = 2000

        /** 服务实例，供 UI 观察状态（简化做法，正式项目应改成 binder/flow）。 */
        @Volatile
        var instance: RelayService? = null
            private set

        /** 启动常驻服务。 */
        fun start(context: Context) {
            val intent = Intent(context, RelayService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            context.startService(
                Intent(context, RelayService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var client: RelayClient? = null

    @Volatile
    var state: String = "stopped"
        private set

    /** 界面注册的回调，用于刷新 UI。 */
    @Volatile
    var onStateChanged: ((String) -> Unit)? = null

    @Volatile
    var onEvent: ((String, JSONObject?) -> Unit)? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                connect()
                return START_STICKY
            }
            else -> connect()
        }
        // START_STICKY：被系统杀掉后自动拉起，这对"常驻"很关键
        return START_STICKY
    }

    private fun connect() {
        // Credentials.load 是 suspend（DataStore），
        // 所以连接逻辑要放进协程里跑。
        scope.launch {
            val creds = CredentialsStore.load(this@RelayService)
            if (creds.psk.isBlank()) {
                onState("stopped", "还没配对")
                stopSelf()
                return@launch
            }

            startForeground(
                NOTIF_ID_SERVICE,
                buildServiceNotification(getString(R.string.notif_connecting)),
            )

            client?.stop()
            val c = RelayClient(
                url = creds.url,
                room = com.mollypaw.remote.crypto.Proto.deriveRoom(creds.psk),
                role = ProtoConst.ROLE_APP,
                devId = creds.devId,
                listener = this@RelayService,
            )
            c.keyMaterial = creds.psk
            client = c
            c.start()
        }
    }

    private fun shutdown() {
        client?.stop()
        client = null
        onState("stopped", "")
    }

    override fun onDestroy() {
        instance = null
        shutdown()
        scope.cancel()
        super.onDestroy()
    }

    // -- RelayClient.Listener --------------------------------------------

    override fun onState(state: String, detail: String) {
        this.state = state
        onStateChanged?.invoke(state)
        val text = when (state) {
            "ready", "connected" -> getString(R.string.notif_connected)
            "reconnecting" -> getString(R.string.notif_disconnected)
            "connecting" -> getString(R.string.notif_connecting)
            else -> getString(R.string.notif_disconnected)
        }
        notify(NOTIF_ID_SERVICE, buildServiceNotification(text))
    }

    override fun onPayload(method: String, params: JSONObject?, msgId: String?) {
        when (method) {
            "chat.delta" -> {
                // 只在实际有内容时提醒，避免流式分片疯狂弹通知
                val text = params?.optString("text", "") ?: ""
                val done = params?.optBoolean("done", false) ?: false
                if (done && text.isNotEmpty()) {
                    notifyMessage(text)
                }
            }
            "approval.request" -> notifyApproval(params)
            else -> Unit
        }
        onEvent?.invoke(method, params)
    }

    override fun onError(code: String, message: String) {
        onState("error", "$code: $message")
        onEvent?.invoke("error", JSONObject().apply {
            put("code", code)
            put("msg", message)
        })
    }

    override fun onPresence(pc: Int, app: Int, observer: Int) {
        onEvent?.invoke("presence", JSONObject().apply {
            put("pc", pc)
            put("app", app)
            put("observer", observer)
        })
    }

    // -- 通知 --------------------------------------------------------------

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                getString(R.string.channel_service_name),
                NotificationManager.IMPORTANCE_LOW,     // 常驻通知别响
            ).apply { description = getString(R.string.channel_service_desc) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MESSAGE,
                getString(R.string.channel_message_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = getString(R.string.channel_message_desc) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_APPROVAL,
                getString(R.string.channel_approval_name),
                NotificationManager.IMPORTANCE_HIGH,    // 审批要立刻看到
            ).apply { description = getString(R.string.channel_approval_desc) }
        )
    }

    private fun contentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun buildServiceNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun notifyMessage(text: String) {
        notify(
            NOTIF_ID_MESSAGE,
            NotificationCompat.Builder(this, CHANNEL_MESSAGE)
                .setContentTitle(getString(R.string.channel_message_name))
                .setContentText(text.take(120))
                .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(600)))
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentIntent(contentIntent())
                .setAutoCancel(true)
                .build()
        )
    }

    private fun notifyApproval(params: JSONObject?) {
        val name = params?.optString("name", "") ?: ""
        val danger = params?.optString("danger_level", "") ?: ""
        val reqId = params?.optString("request_id", "") ?: ""
        if (reqId.isEmpty()) return

        val text = "AI 想执行 $name" + if (danger == "dangerous") "（风险操作）" else ""
        val id = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            NOTIF_ID_APPROVAL_BASE + (reqId.hashCode() and 0xff)
        } else {
            NOTIF_ID_APPROVAL_BASE
        }
        notify(
            id,
            NotificationCompat.Builder(this, CHANNEL_APPROVAL)
                .setContentTitle(getString(R.string.channel_approval_name))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(contentIntent())
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
        )
    }

    private fun notify(id: Int, n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 需要 POST_NOTIFICATIONS 权限，没给就静默跳过
            val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted && id != NOTIF_ID_SERVICE) return
        }
        getSystemService(NotificationManager::class.java).notify(id, n)
    }

    // -- 便利入口 ----------------------------------------------------------

    /** 给 UI 用：发一个请求。 */
    suspend fun request(method: String, params: JSONObject?, timeoutMs: Long = 20_000): JSONObject? =
        client?.request(method, params, timeoutMs)

    fun isConnected(): Boolean = client?.keyReady == true
}
