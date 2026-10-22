package com.mollypaw.remote.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.util.UUID

private val Context.dataStore by preferencesDataStore(name = "mollypaw_remote")

/**
 * 凭据存储。
 *
 * 存什么：
 *   - url  中继地址
 *   - psk  主密钥（**服务端在配对时下发的**）
 *   - token 设备 token
 *   - devId / devName  设备身份
 *   - role 控制端 / 只读端
 *
 * 安全说明：DataStore 本身是明文存储在应用私有目录里。
 * 更严格的做法是放进 Android Keystore 加密后再写。
 * 这里的取舍是：这是自用工具，且 PSK 本来就在用户自己手机上；
 * 但**不能**把这个数据导出/分享。
 */
data class Credentials(
    val url: String = "",
    val psk: String = "",
    val token: String = "",
    val devId: String = "",
    val devName: String = "",
    val role: String = "app",
) {
    val paired: Boolean get() = token.isNotBlank() && psk.isNotBlank()
    val isObserver: Boolean get() = role == "observer"
}

object CredentialsStore {
    private val KEY_URL = stringPreferencesKey("url")
    private val KEY_PSK = stringPreferencesKey("psk")
    private val KEY_TOKEN = stringPreferencesKey("token")
    private val KEY_DEV_ID = stringPreferencesKey("dev_id")
    private val KEY_DEV_NAME = stringPreferencesKey("dev_name")
    private val KEY_ROLE = stringPreferencesKey("role")

    suspend fun load(context: Context): Credentials {
        val prefs = context.dataStore.data.first()
        return Credentials(
            url = prefs[KEY_URL] ?: "",
            psk = prefs[KEY_PSK] ?: "",
            token = prefs[KEY_TOKEN] ?: "",
            devId = prefs[KEY_DEV_ID] ?: "",
            devName = prefs[KEY_DEV_NAME] ?: "",
            role = prefs[KEY_ROLE] ?: "app",
        )
    }

    suspend fun save(context: Context, creds: Credentials) {
        context.dataStore.edit { prefs ->
            prefs[KEY_URL] = creds.url
            prefs[KEY_PSK] = creds.psk
            prefs[KEY_TOKEN] = creds.token
            prefs[KEY_DEV_ID] = creds.devId
            prefs[KEY_DEV_NAME] = creds.devName
            prefs[KEY_ROLE] = creds.role
        }
    }

    suspend fun clear(context: Context) {
        context.dataStore.edit { it.clear() }
    }

    /** 首次运行时生成一个稳定的设备 id。 */
    fun newDeviceId(): String = UUID.randomUUID().toString()
}
