package com.mollypaw.remote.crypto

/**
 * 协议常量与方法表 —— PROTOCOL.md 的可执行版本。
 *
 * 这里的每个值都必须和 Python 端 agent/relay/{crypto,protocol}.py
 * 保持一致。改了任何一处，三个实现都要同步改，否则对不上。
 */
object Proto {

    const val VERSION = 1

    // -- 帧类型 --
    const val T_HELLO = "hello"
    const val T_DATA = "data"
    const val T_PING = "ping"
    const val T_PONG = "pong"
    const val T_BYE = "bye"
    const val T_ERROR = "error"
    const val T_PRESENCE = "presence"

    // -- 角色 --
    const val ROLE_PC = "pc"
    const val ROLE_APP = "app"
    const val ROLE_OBSERVER = "observer"

    // -- 错误码 --
    const val E_VERSION = "version"
    const val E_BAD_FRAME = "bad_frame"
    const val E_BAD_ROOM = "bad_room"
    const val E_BAD_ROLE = "bad_role"
    const val E_OFFLINE = "offline"
    const val E_FORBIDDEN = "forbidden"
    const val E_NOT_FOUND = "not_found"
    const val E_INTERNAL = "internal"
    const val E_TOO_LARGE = "too_large"
    const val E_RATE_LIMITED = "rate_limited"

    // -- 配对码 --
    const val PAIR_SEED_LEN = 32
    const val REG_TOKEN_LEN = 32
    const val PAIRING_CODE_PREFIX = "MP1."
    const val INFO_ROOM = "mollypaw/v1/room"
    const val INFO_SESSION = "mollypaw/v1/session"
    const val INFO_PAIR_KEY = "mollypaw/v1/pair-key"
    const val INFO_REG_TOKEN = "mollypaw/v1/reg-token"

    // -- 心跳 / 重连 --
    const val HEARTBEAT_SECONDS = 20L
    const val HEARTBEAT_TIMEOUT = 40L
    const val AUTH_TS_SKEW_SECONDS = 120L
    const val MAX_FRAME_BYTES = 1024 * 1024

    /** 重连退避（秒），与 Python 端一致：1,2,4,8,16,30,… */
    fun backoffSeconds(attempt: Int): Long {
        val base = 1L shl minOf(attempt, 5)
        return minOf(base, 30L)
    }

    /**
     * 配对码解析：MP1.<b64(pair_seed)>.<room>
     *
     * 与 Python 的 parse_pairing_code 行为一致：
     * 对首尾空白、换行、空格宽容（用户从聊天软件粘贴常带这些）。
     */
    fun parsePairingCode(code: String): PairingCode {
        val text = code.trim()
            .replace("\n", "")
            .replace("\r", "")
            .replace(" ", "")
        require(text.startsWith(PAIRING_CODE_PREFIX)) { "配对码格式不对（应以 MP1. 开头）" }

        val body = text.removePrefix(PAIRING_CODE_PREFIX)
        val parts = body.split(".")
        require(parts.size == 2) { "配对码应该有两段（pair_seed.room），实际 ${parts.size} 段" }

        val seed = try {
            Envelope.b64d(parts[0])
        } catch (e: Exception) {
            throw IllegalArgumentException("配对码里的种子不是合法 base64：${e.message}")
        }
        require(seed.size == PAIR_SEED_LEN) {
            "配对码里的种子长度不对（${seed.size} 字节）"
        }

        val room = parts[1].lowercase()
        require(room.length == 32 && room.all { it in "0123456789abcdef" }) {
            "配对码里的房间号不是 32 位 hex"
        }
        return PairingCode(seed, room)
    }

    /** 由配对种子派生配对密钥（手机用它代替 PSK 派生会话密钥）。 */
    fun derivePairKey(seed: ByteArray): ByteArray =
        Hkdf.derive(seed, INFO_PAIR_KEY.toByteArray(Charsets.UTF_8), 32, ByteArray(0))

    /** 由配对种子派生一次性注册凭据。 */
    fun deriveRegToken(seed: ByteArray): ByteArray =
        Hkdf.derive(seed, INFO_REG_TOKEN.toByteArray(Charsets.UTF_8), REG_TOKEN_LEN, ByteArray(0))

    /** PSK -> room（32 位小写 hex）。 */
    fun deriveRoom(psk: String): String {
        require(pskOk(psk)) { "PSK 强度不足：${pskProblems(psk).joinToString("；")}" }
        val digest = Hkdf.derive(
            psk.toByteArray(Charsets.UTF_8),
            INFO_ROOM.toByteArray(Charsets.UTF_8),
            16,
            ByteArray(0),
        )
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * 会话密钥派生（PROTOCOL.md §4.3）。
     *
     * salt 顺序**固定 hs_pc ‖ hs_app**，与谁发送无关 —— 两端必须都按
     * pc 在前。写反了就两边算不出同一把钥匙。
     */
    fun deriveSessionKey(
        myPriv: ByteArray,
        peerPub: ByteArray,
        psk: String,
        hsPc: ByteArray,
        hsApp: ByteArray,
    ): ByteArray {
        require(hsPc.size == Envelope.HS_LEN) { "握手 nonce 必须是 ${Envelope.HS_LEN} 字节" }
        require(hsApp.size == Envelope.HS_LEN) { "握手 nonce 必须是 ${Envelope.HS_LEN} 字节" }
        val shared = X25519.shared(myPriv, peerPub)
        val ikm = shared + psk.toByteArray(Charsets.UTF_8)
        val salt = hsPc + hsApp
        return Hkdf.derive(
            ikm,
            INFO_SESSION.toByteArray(Charsets.UTF_8),
            32,
            salt,
        )
    }

    // -- PSK 强度（与 Python 端一致：>=16 位，含大小写与数字，不要求符号）--

    const val PSK_MIN_LEN = 16

    fun pskProblems(psk: String): List<String> {
        val problems = mutableListOf<String>()
        if (psk.length < PSK_MIN_LEN) {
            problems.add("密钥太短：至少 $PSK_MIN_LEN 位，现在 ${psk.length} 位")
        }
        if (psk.none { it.isLowerCase() }) problems.add("密钥需要至少一个小写字母")
        if (psk.none { it.isUpperCase() }) problems.add("密钥需要至少一个大写字母")
        if (psk.none { it.isDigit() }) problems.add("密钥需要至少一个数字")
        return problems
    }

    fun pskOk(psk: String): Boolean = pskProblems(psk).isEmpty()
}

data class PairingCode(val pairSeed: ByteArray, val room: String) {
    // data class 带 ByteArray 要手写 equals/hashCode，否则比较的是引用
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairingCode) return false
        return pairSeed.contentEquals(other.pairSeed) && room == other.room
    }

    override fun hashCode(): Int = 31 * pairSeed.contentHashCode() + room.hashCode()
}
