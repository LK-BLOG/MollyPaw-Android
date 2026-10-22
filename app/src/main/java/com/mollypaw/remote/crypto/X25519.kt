package com.mollypaw.remote.crypto

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom

/**
 * X25519 密钥交换 —— PROTOCOL.md §4.1。
 *
 * 为什么用 BouncyCastle：**Android JCA 没有原生 X25519**。
 * （有些厂商在 API 33+ 塞了 "XDH" 算法，但兼容性完全不可靠，
 * 而且 minSdk 26 根本指望不上。）
 * BouncyCastle 是纯 Java 实现，全平台一致。
 *
 * 密钥都是 **32 字节裸数据**，与 Python cryptography 库的
 * Raw 编码格式一致 —— 这点很关键，不一致就对不上。
 */
object X25519 {

    private val random = SecureRandom()

    data class KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

    /** 生成一对临时密钥。 */
    fun generate(): KeyPair {
        val priv = X25519PrivateKeyParameters(random)
        return KeyPair(priv.encoded, priv.generatePublicKey().encoded)
    }

    /** 从 32 字节裸私钥推出公钥。 */
    fun publicFromPrivate(privateKey: ByteArray): ByteArray {
        require(privateKey.size == 32) { "X25519 私钥必须是 32 字节" }
        val priv = X25519PrivateKeyParameters(privateKey, 0)
        return priv.generatePublicKey().encoded
    }

    /**
     * 计算共享秘密（32 字节）。
     *
     * BouncyCastle 在结果是全零（低阶点攻击）时会抛异常，正好当检测用 ——
     * 不要吞掉这个异常。
     */
    fun shared(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        require(privateKey.size == 32) { "X25519 私钥必须是 32 字节" }
        require(peerPublicKey.size == 32) { "X25519 公钥必须是 32 字节" }
        val priv = X25519PrivateKeyParameters(privateKey, 0)
        val peer = X25519PublicKeyParameters(peerPublicKey, 0)
        val out = ByteArray(32)
        priv.generateSecret(peer, out, 0)
        return out
    }

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }
}
