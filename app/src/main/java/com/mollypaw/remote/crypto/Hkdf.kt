package com.mollypaw.remote.crypto

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF-SHA256（RFC 5869）—— PROTOCOL.md §4 的实现。
 *
 * 为什么手写：Android JCA 到 API 35 都**没有内置 HKDF**。
 * （javax.crypto.KDF 是 API 33+ 才有，且各厂商支持不一，
 * 为了 minSdk 26 能在所有机器上跑，这里自己实现。）
 *
 * **必须与 Python 端 agent/relay/crypto.py 的 hkdf() 输出完全一致。**
 * tests 里有固定向量测试（RFC 5869 附录 A.1），对不上就立刻红。
 */
object Hkdf {

    /**
     * @param ikm  输入密钥材料
     * @param info 上下文信息
     * @param length 输出长度（字节）
     * @param salt 盐；**null 与空数组等价**（RFC 5869：HMAC 对不足 block size
     *             的密钥右侧补零，所以全零盐 == 空盐）。
     */
    fun derive(
        ikm: ByteArray,
        info: ByteArray,
        length: Int,
        salt: ByteArray? = null,
    ): ByteArray {
        val effectiveSalt = if (salt == null || salt.isEmpty()) {
            ByteArray(HASH_LEN)   // 32 字节全零
        } else {
            salt
        }

        // Extract
        val prk = hmacSha256(effectiveSalt, ikm)

        // Expand
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val chunk = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, chunk)
            pos += chunk
            counter++
            if (counter > 255) break   // RFC 5869 上限
        }
        return out
    }

    fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    fun sha256Hex(data: ByteArray): String =
        sha256(data).joinToString("") { "%02x".format(it) }

    /** HMAC-SHA256 的常数时间比较，防时序侧信道。 */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    const val HASH_LEN = 32
}
