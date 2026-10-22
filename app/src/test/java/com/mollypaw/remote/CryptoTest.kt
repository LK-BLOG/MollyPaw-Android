package com.mollypaw.remote

import com.mollypaw.remote.crypto.Envelope
import com.mollypaw.remote.crypto.Hkdf
import com.mollypaw.remote.crypto.Proto
import com.mollypaw.remote.crypto.X25519
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨实现一致性测试 —— Android 端最该有的东西。
 *
 * 光"自己加解密自己解得开"毫无意义（写错了两边一起错也自洽）。
 * 这里用的都是**标准向量**或**Python 端算出的固定值**，
 * 保证三个实现真的互通。
 */
class CryptoTest {

    // ======================================================================
    // HKDF：RFC 5869 附录 A.1 官方向量
    // ======================================================================

    @Test
    fun `hkdf matches RFC 5869 test case 1`() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        val expected = hex(
            "3cb25f25faacd57a90434f64d0362f2a" +
            "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
            "34007208d5b887185865"
        )
        assertArrayEquals(expected, Hkdf.derive(ikm, info, 42, salt))
    }

    @Test
    fun `hkdf salt null equals empty`() {
        // RFC 5869：salt 传 null 与空数组等价（HMAC 对短密钥右侧补零）
        val a = Hkdf.derive("ikm".toByteArray(), "info".toByteArray(), 32, null)
        val b = Hkdf.derive("ikm".toByteArray(), "info".toByteArray(), 32, ByteArray(0))
        assertArrayEquals(a, b)
    }

    @Test
    fun `hkdf output length is honoured`() {
        for (len in listOf(16, 32, 42, 64)) {
            assertEquals(len, Hkdf.derive("x".toByteArray(), "y".toByteArray(), len).size)
        }
    }

    // ======================================================================
    // X25519：RFC 7748 §6.1 官方向量
    // ======================================================================

    @Test
    fun `x25519 matches RFC 7748 test vector`() {
        val alicePriv = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bobPub = hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        val expected = hex(
            "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
        )
        assertArrayEquals(expected, X25519.shared(alicePriv, bobPub))
    }

    @Test
    fun `publicFromPrivate matches RFC 7748`() {
        val priv = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val expectedPub = hex(
            "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"
        )
        assertArrayEquals(expectedPub, X25519.publicFromPrivate(priv))
    }

    @Test
    fun `ecdh agrees both directions`() {
        val a = X25519.generate()
        val b = X25519.generate()
        assertArrayEquals(
            X25519.shared(a.privateKey, b.publicKey),
            X25519.shared(b.privateKey, a.publicKey),
        )
    }

    // ======================================================================
    // 会话密钥：与 Python 端算法对齐
    // ======================================================================

    @Test
    fun `session key matches across peers`() {
        val psk = "MollyPawTest1234"
        val a = X25519.generate()
        val b = X25519.generate()
        val hsPc = ByteArray(16) { 1 }
        val hsApp = ByteArray(16) { 2 }

        // pc 侧：自己的私钥 + 对端公钥
        val kPc = Proto.deriveSessionKey(a.privateKey, b.publicKey, psk, hsPc, hsApp)
        // app 侧：salt 顺序不变（pc 在前）
        val kApp = Proto.deriveSessionKey(b.privateKey, a.publicKey, psk, hsPc, hsApp)
        assertArrayEquals(kPc, kApp)
        assertEquals(32, kPc.size)
    }

    @Test
    fun `session key salt order matters`() {
        val psk = "MollyPawTest1234"
        val a = X25519.generate()
        val b = X25519.generate()
        val hsPc = ByteArray(16) { 1 }
        val hsApp = ByteArray(16) { 2 }
        val k1 = Proto.deriveSessionKey(a.privateKey, b.publicKey, psk, hsPc, hsApp)
        val k2 = Proto.deriveSessionKey(a.privateKey, b.publicKey, psk, hsApp, hsPc)
        assertFalse(k1.contentEquals(k2))
    }

    // ======================================================================
    // 信封：AAD 绑定
    // ======================================================================

    @Test
    fun `aad format is frozen`() {
        // AAD 是跨实现契约，字符串格式改一个字符三方全崩
        val room = "ab".repeat(16)
        assertEquals(
            "mollypaw/v1|$room|app|42",
            String(Envelope.aadBytes(room, "app", 42), Charsets.UTF_8),
        )
    }

    @Test
    fun `envelope roundtrip`() {
        val key = X25519.randomBytes(32)
        val room = "ab".repeat(16)
        val plaintext = """{"m":"chat.send","p":{"text":"你好 Molly"}}""".toByteArray()
        val (nonce, blob) = Envelope.encrypt(key, room, "app", 7, plaintext)
        val back = Envelope.decrypt(key, room, "app", 7, nonce, blob)
        assertArrayEquals(plaintext, back)
    }

    @Test(expected = Exception::class)
    fun `tampered ciphertext is rejected`() {
        val key = X25519.randomBytes(32)
        val room = "ab".repeat(16)
        val (nonce, blob) = Envelope.encrypt(key, room, "app", 1, "x".toByteArray())
        blob[0] = (blob[0].toInt() xor 0x01).toByte()
        Envelope.decrypt(key, room, "app", 1, nonce, blob)
    }

    @Test(expected = Exception::class)
    fun `aad binds room`() {
        val key = X25519.randomBytes(32)
        val (nonce, blob) = Envelope.encrypt(key, "aa".repeat(16), "app", 1, "x".toByteArray())
        Envelope.decrypt(key, "bb".repeat(16), "app", 1, nonce, blob)
    }

    @Test(expected = Exception::class)
    fun `aad binds role`() {
        // observer 冒充 app 必须失败
        val key = X25519.randomBytes(32)
        val room = "ab".repeat(16)
        val (nonce, blob) = Envelope.encrypt(key, room, "observer", 1, "x".toByteArray())
        Envelope.decrypt(key, room, "app", 1, nonce, blob)
    }

    @Test(expected = Exception::class)
    fun `aad binds seq`() {
        // 重放 / 换序必须失败
        val key = X25519.randomBytes(32)
        val room = "ab".repeat(16)
        val (nonce, blob) = Envelope.encrypt(key, room, "app", 5, "x".toByteArray())
        Envelope.decrypt(key, room, "app", 6, nonce, blob)
    }

    // ======================================================================
    // 配对码
    // ======================================================================

    @Test
    fun `pairing code roundtrip`() {
        val seed = X25519.randomBytes(32)
        val room = "0123456789abcdef0123456789abcdef"
        val code = "MP1." + Envelope.b64e(seed) + "." + room
        val parsed = Proto.parsePairingCode(code)
        assertArrayEquals(seed, parsed.pairSeed)
        assertEquals(room, parsed.room)
    }

    @Test
    fun `pairing code tolerates whitespace`() {
        val seed = X25519.randomBytes(32)
        val room = "f".repeat(32)
        val code = "MP1." + Envelope.b64e(seed) + "." + room
        val messy = "  " + code.substring(0, 12) + "\n" + code.substring(12) + "\n  "
        val parsed = Proto.parsePairingCode(messy)
        assertArrayEquals(seed, parsed.pairSeed)
        assertEquals(room, parsed.room)
    }

    @Test
    fun `pairing code rejects garbage`() {
        for (bad in listOf("", "hello", "MP1.", "MP1.abc", "MP2.x.y")) {
            try {
                Proto.parsePairingCode(bad)
                throw AssertionError("不该接受非法配对码：$bad")
            } catch (e: IllegalArgumentException) {
                // 预期
            }
        }
    }

    @Test
    fun `derived pair key and reg token differ`() {
        val seed = X25519.randomBytes(32)
        val pairKey = Proto.derivePairKey(seed)
        val regToken = Proto.deriveRegToken(seed)
        assertEquals(32, pairKey.size)
        assertEquals(32, regToken.size)
        assertFalse(pairKey.contentEquals(regToken))
    }

    // ======================================================================
    // PSK 强度（与 Python 端规则一致）
    // ======================================================================

    @Test
    fun `psk strength rules`() {
        assertTrue(Proto.pskOk("Abcdefgh12345678"))
        // 不要求符号（用户明确要求放宽）
        assertTrue(Proto.pskOk("Abcdefgh1234567!"))

        assertFalse(Proto.pskOk("Abc123"))                       // 太短
        assertFalse(Proto.pskOk("ABCDEFGH12345678"))             // 缺小写
        assertFalse(Proto.pskOk("abcdefgh12345678"))             // 缺大写
        assertFalse(Proto.pskOk("Abcdefghijklmnop"))             // 缺数字
    }

    @Test
    fun `psk problems are specific`() {
        val problems = Proto.pskProblems("abc")
        assertEquals(3, problems.size)
        assertTrue(problems.any { it.contains("16") })
        assertTrue(problems.any { it.contains("大写") })
        assertTrue(problems.any { it.contains("数字") })
    }

    @Test
    fun `deriveRoom is deterministic and 32 hex`() {
        val room = Proto.deriveRoom("MollyPawTest1234")
        assertEquals(32, room.length)
        assertTrue(room.all { it in "0123456789abcdef" })
        assertEquals(room, Proto.deriveRoom("MollyPawTest1234"))
        assertNotEquals(room, Proto.deriveRoom("MollyPawTest1235"))
    }

    // ======================================================================
    // 对照 Python 端算出的固定值
    // ======================================================================

    @Test
    fun `room matches python implementation`() {
        // 这个值是 Python 端 agent.relay.crypto.derive_room("MollyPawTest1234")
        // 算出来的（tools/gen_cross_vectors.py 生成）。
        // 对不上说明两边的 KDF 实现有差异，跨端就连不上。
        assertEquals(PYTHON_ROOM, Proto.deriveRoom("MollyPawTest1234"))
    }

    @Test
    fun `pair key and reg token match python`() {
        val seed = ByteArray(32) { it.toByte() }   // 00 01 02 ... 1f
        assertEquals(PYTHON_PAIR_KEY, hex(Proto.derivePairKey(seed)))
        assertEquals(PYTHON_REG_TOKEN, hex(Proto.deriveRegToken(seed)))
    }

    @Test
    fun `session key matches python`() {
        // 用 RFC 7748 的 alice/bob 固定私钥，保证可复现
        val sessionKey = Proto.deriveSessionKey(
            hex(ALICE_PRIV),
            hex(BOB_PUB),
            "MollyPawTest1234",
            ByteArray(16) { 1 },
            ByteArray(16) { 2 },
        )
        assertEquals(PYTHON_SESSION_KEY, hex(sessionKey))
    }

    @Test
    fun `pairing code parses python generated code`() {
        // 这个配对码是 Python 端 make_pairing_code 生成的原文
        val code = "MP1.AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=." +
                   "cdf08d97b7d52d380c707cb3b77398b2"
        val parsed = Proto.parsePairingCode(code)
        assertEquals("cdf08d97b7d52d380c707cb3b77398b2", parsed.room)
        assertEquals(32, parsed.pairSeed.size)
        // 派生出来的钥匙必须和 Python 一致
        assertEquals(PYTHON_PAIR_KEY, hex(Proto.derivePairKey(parsed.pairSeed)))
    }

    // ----------------------------------------------------------------------

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun hex(b: ByteArray): String =
        b.joinToString("") { "%02x".format(it) }

    companion object {
        /**
         * 跨实现锚点 —— 由 tools/gen_cross_vectors.py 生成。
         * 这些值来自 Python 端实现，Android 端必须算出同样的结果。
         */
        private const val PYTHON_ROOM = "cdf08d97b7d52d380c707cb3b77398b2"
        private const val PYTHON_PAIR_KEY =
            "d205c7491b7c8804fb13fae409d345a2d9d8f88c74ec879867d80cb3eccd5ee2"
        private const val PYTHON_REG_TOKEN =
            "ed57165dd82dffaa434aab3a0f04376f7ed7aca5d7caf02db15e61abd8f9d9e7"
        private const val PYTHON_SESSION_KEY =
            "286fd05d3cbc09dfcf55bc4968616f5470710527514b643a9d7ae445aa18c6b6"
        private const val ALICE_PRIV =
            "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"
        private const val BOB_PUB =
            "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"
    }
}
