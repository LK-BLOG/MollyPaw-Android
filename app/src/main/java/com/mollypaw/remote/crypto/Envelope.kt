package com.mollypaw.remote.crypto

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 信封加解密 —— PROTOCOL.md §3.2。
 *
 * 与 Python 端 agent/relay/crypto.py 的 encrypt_payload / decrypt_payload
 * **逐字节兼容**，尤其是：
 *   - nonce 12 字节
 *   - GCM tag 16 字节（附在密文尾部，JCA 默认就是这个布局）
 *   - AAD = utf8("mollypaw/v1|" + room + "|" + from + "|" + seq)
 *
 * AAD 是跨实现契约，字符串格式改一个字符三方全崩。
 */
object Envelope {

    const val PROTOCOL_VERSION = 1
    const val NONCE_LEN = 12
    const val TAG_LEN = 16
    const val KEY_LEN = 32
    const val HS_LEN = 16
    const val AAD_PREFIX = "mollypaw/v1|"

    /**
     * base64 标准表 + padding，与 Python 的 base64.b64encode 一致。
     *
     * 用 **java.util.Base64** 而不是 android.util.Base64：
     * 后者在单元测试里是 stub（未 Robolectric 时返回 null），
     * 跨实现一致性测试会直接 NPE。
     */
    fun b64e(raw: ByteArray): String =
        Base64.getEncoder().encodeToString(raw)

    /** 解码时容忍缺 padding（Python 端有同样的宽容）。 */
    fun b64d(text: String): ByteArray =
        Base64.getMimeDecoder().decode(text)

    /**
     * 构造 AAD：锁定「哪间房、谁发的、第几帧」。
     *
     * 防的是：中继把 A 房间的密文搬去 B 房间、把 observer 的帧冒充成 app、
     * 以及重放与乱序。
     */
    fun aadBytes(room: String, senderRole: String, seq: Long): ByteArray =
        "$AAD_PREFIX$room|$senderRole|$seq".toByteArray(Charsets.UTF_8)

    /** 加密明文，返回 (nonce, blob)，blob = ciphertext ‖ tag。 */
    fun encrypt(
        key: ByteArray,
        room: String,
        senderRole: String,
        seq: Long,
        plaintext: ByteArray,
    ): Pair<ByteArray, ByteArray> {
        require(key.size == KEY_LEN) { "会话密钥必须是 32 字节" }
        val nonce = X25519.randomBytes(NONCE_LEN)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_LEN * 8, nonce),
        )
        cipher.updateAAD(aadBytes(room, senderRole, seq))
        return nonce to cipher.doFinal(plaintext)
    }

    /**
     * 解密。
     *
     * 认证失败（改过密文、AAD 不对、角色冒充、序号错位）会抛
     * AEADBadTagException —— 调用方**必须**当硬错误处理，
     * 不要退回明文，也不要重试。
     */
    fun decrypt(
        key: ByteArray,
        room: String,
        senderRole: String,
        seq: Long,
        nonce: ByteArray,
        blob: ByteArray,
    ): ByteArray {
        require(key.size == KEY_LEN) { "会话密钥必须是 32 字节" }
        require(nonce.size == NONCE_LEN) { "nonce 必须是 $NONCE_LEN 字节" }
        require(blob.size >= TAG_LEN) { "密文短于 GCM tag" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_LEN * 8, nonce),
        )
        cipher.updateAAD(aadBytes(room, senderRole, seq))
        return cipher.doFinal(blob)
    }
}
