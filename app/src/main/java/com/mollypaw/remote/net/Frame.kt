package com.mollypaw.remote.net

import org.json.JSONObject

/**
 * 协议帧 —— 与 Python 端 agent/relay/protocol.py 一一对应。
 *
 * 所有帧都是**单个 JSON 文本消息**（不用二进制帧）。
 */
data class Frame(
    val v: Int = ProtoConst.VERSION,
    val t: String,
    val r: String? = null,
    val f: String? = null,
    val seq: Long = 0,
    val id: String? = null,
    val n: String? = null,          // base64 nonce
    val c: String? = null,          // base64 ciphertext‖tag
    val dev: String? = null,
    val eph: String? = null,
    val hs: String? = null,
    val code: String? = null,       // error 帧用
    val msg: String? = null,        // error 帧用
    val pc: Int? = null,            // presence 帧用
    val app: Int? = null,
    val observer: Int? = null,
    val ts: Long? = null,
    val reason: String? = null,
    val mode: String? = null,
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("v", v)
        o.put("t", t)
        r?.let { o.put("r", it) }
        f?.let { o.put("f", it) }
        if (seq != 0L) o.put("seq", seq)
        id?.let { o.put("id", it) }
        n?.let { o.put("n", it) }
        c?.let { o.put("c", it) }
        dev?.let { o.put("dev", it) }
        eph?.let { o.put("eph", it) }
        hs?.let { o.put("hs", it) }
        code?.let { o.put("code", it) }
        msg?.let { o.put("msg", it) }
        pc?.let { o.put("pc", it) }
        app?.let { o.put("app", it) }
        observer?.let { o.put("observer", it) }
        ts?.let { o.put("ts", it) }
        reason?.let { o.put("reason", it) }
        mode?.let { o.put("mode", it) }
        return o.toString()
    }

    companion object {
        fun parse(text: String): Frame? = try {
            val o = JSONObject(text)
            Frame(
                v = o.optInt("v", ProtoConst.VERSION),
                t = o.optString("t", ""),
                r = o.optStringOrNull("r"),
                f = o.optStringOrNull("f"),
                seq = o.optLong("seq", 0),
                id = o.optStringOrNull("id"),
                n = o.optStringOrNull("n"),
                c = o.optStringOrNull("c"),
                dev = o.optStringOrNull("dev"),
                eph = o.optStringOrNull("eph"),
                hs = o.optStringOrNull("hs"),
                code = o.optStringOrNull("code"),
                msg = o.optStringOrNull("msg"),
                pc = o.optIntOrNull("pc"),
                app = o.optIntOrNull("app"),
                observer = o.optIntOrNull("observer"),
                ts = o.optLongOrNull("ts"),
                reason = o.optStringOrNull("reason"),
                mode = o.optStringOrNull("mode"),
            )
        } catch (e: Exception) {
            null
        }

        fun hello(
            room: String,
            role: String,
            dev: String,
            ephB64: String,
            hsB64: String,
        ) = Frame(
            t = ProtoConst.T_HELLO, r = room, f = role, dev = dev,
            eph = ephB64, hs = hsB64,
            ts = System.currentTimeMillis(),
        )

        fun data(
            room: String,
            role: String,
            seq: Long,
            msgId: String,
            nonceB64: String,
            cipherB64: String,
        ) = Frame(
            t = ProtoConst.T_DATA, r = room, f = role, seq = seq,
            id = msgId, n = nonceB64, c = cipherB64,
        )

        fun ping(room: String, role: String) = Frame(
            t = ProtoConst.T_PING, r = room, f = role,
            ts = System.currentTimeMillis(),
        )

        fun bye(room: String, role: String, reason: String = "") = Frame(
            t = ProtoConst.T_BYE, r = room, f = role, reason = reason,
        )
    }
}

/*
 * 顶层扩展函数。
 * 之前把它们放在 Frame 类里（成了 Frame 的成员扩展），
 * 于是调用点 `o.optStringOrNull("r")`（o 是 JSONObject）就找不到 —— 编译报
 * "Unresolved reference 'optStringOrNull'"。
 */
private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) getInt(key) else null

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) getLong(key) else null

/** 协议常量（避免与 Proto 重名，这里只放帧相关）。 */
object ProtoConst {
    const val VERSION = 1

    const val T_HELLO = "hello"
    const val T_DATA = "data"
    const val T_PING = "ping"
    const val T_PONG = "pong"
    const val T_BYE = "bye"
    const val T_ERROR = "error"
    const val T_PRESENCE = "presence"

    const val ROLE_PC = "pc"
    const val ROLE_APP = "app"
    const val ROLE_OBSERVER = "observer"
}
