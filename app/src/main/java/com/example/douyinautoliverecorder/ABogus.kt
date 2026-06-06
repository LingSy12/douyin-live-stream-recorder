package com.example.douyinautoliverecorder

import kotlin.math.ceil

/**
 * Kotlin port of Douyin's `a_bogus` request signature.
 *
 * Ported from ihmily/DouyinLiveRecorder `src/ab_sign.py` (SM3 + RC4 + custom base64). Kept as a
 * pure-JVM object (no Android dependencies) so it can be unit-tested against reference vectors on
 * the host JVM. The millisecond timestamp is injectable so the output is deterministic in tests;
 * production callers omit it and get [System.currentTimeMillis].
 *
 * If Douyin changes the signing algorithm this must be re-ported; the direct-API probe falls back
 * to the WebView probe when the signed request is rejected.
 */
object ABogus {

    private const val WINDOW_ENV =
        "1920|1080|1920|1040|0|30|0|0|1872|92|1920|1040|1857|92|1|24|Win32"

    private val ENCODING_TABLES = mapOf(
        "s0" to "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=",
        "s1" to "Dkdpgh4ZKsQB80/Mfvw36XI1R25+WUAlEi7NLboqYTOPuzmFjJnryx9HVGcaStCe=",
        "s2" to "Dkdpgh4ZKsQB80/Mfvw36XI1R25-WUAlEi7NLboqYTOPuzmFjJnryx9HVGcaStCe=",
        "s3" to "ckdp1h4ZKsUB80/Mfvw36XIgR25+WQAlEi7NLboqYTOPuzmFjJnryx9HVGDaStCe",
        "s4" to "Dkdpgh2ZmsQB80/MfvV36XI1R45-WUAlEixNLwoqYTOPuzKFjJnry79HbGcaStCe"
    )

    /** Returns the `a_bogus` value to append to a Douyin web API query string. */
    fun sign(
        urlSearchParams: String,
        userAgent: String,
        startTimeMs: Long = System.currentTimeMillis()
    ): String {
        val prefix = generateRandomStr()
        val body = generateRc4BbStr(urlSearchParams, userAgent, WINDOW_ENV, startTimeMs)
        return resultEncrypt(prefix + body, "s4") + "="
    }

    // ----------------------------------------------------------------- RC4
    private fun rc4(plaintext: IntArray, key: IntArray): IntArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + key[i % key.size]) and 0xFF
            val t = s[i]; s[i] = s[j]; s[j] = t
        }
        var a = 0
        var b = 0
        val out = IntArray(plaintext.size)
        for (idx in plaintext.indices) {
            a = (a + 1) and 0xFF
            b = (b + s[a]) and 0xFF
            val t = s[a]; s[a] = s[b]; s[b] = t
            out[idx] = s[(s[a] + s[b]) and 0xFF] xor plaintext[idx]
        }
        return out
    }

    // ----------------------------------------------------------------- SM3 (standard)
    private val SM3_IV = intArrayOf(
        1937774191, 1226093241, 388252375, 3666478592.toInt(),
        2842636476.toInt(), 372324522, 3817729613.toInt(), 2969243214.toInt()
    )

    private fun rotl(x: Int, nIn: Int): Int {
        val n = nIn % 32
        if (n == 0) return x
        return (x shl n) or (x ushr (32 - n))
    }

    private fun tj(j: Int): Int = if (j < 16) 2043430169 else 2055708042

    private fun ffj(j: Int, x: Int, y: Int, z: Int): Int =
        if (j < 16) x xor y xor z else (x and y) or (x and z) or (y and z)

    private fun ggj(j: Int, x: Int, y: Int, z: Int): Int =
        if (j < 16) x xor y xor z else (x and y) or (x.inv() and z)

    /** One-shot standard SM3 over a byte sequence (values 0..255). Returns 32 bytes (0..255). */
    private fun sm3(message: IntArray): IntArray {
        val padded = ArrayList<Int>(message.size + 72)
        for (v in message) padded.add(v and 0xFF)
        val bitLen = message.size.toLong() * 8L
        padded.add(0x80)
        while (padded.size % 64 != 56) padded.add(0)
        for (i in 7 downTo 0) padded.add(((bitLen ushr (8 * i)) and 0xFF).toInt())

        val reg = SM3_IV.copyOf()
        val blocks = padded.size / 64
        val w = IntArray(132)
        for (blk in 0 until blocks) {
            val off = blk * 64
            for (t in 0 until 16) {
                val p = off + 4 * t
                w[t] = (padded[p] shl 24) or (padded[p + 1] shl 16) or (padded[p + 2] shl 8) or padded[p + 3]
            }
            for (j in 16 until 68) {
                var a = w[j - 16] xor w[j - 9] xor rotl(w[j - 3], 15)
                a = a xor rotl(a, 15) xor rotl(a, 23)
                w[j] = a xor rotl(w[j - 13], 7) xor w[j - 6]
            }
            for (j in 0 until 64) {
                w[j + 68] = w[j] xor w[j + 4]
            }
            var a = reg[0]; var b = reg[1]; var c = reg[2]; var d = reg[3]
            var e = reg[4]; var f = reg[5]; var g = reg[6]; var h = reg[7]
            for (j in 0 until 64) {
                val ss1 = rotl((rotl(a, 12) + e + rotl(tj(j), j)), 7)
                val ss2 = ss1 xor rotl(a, 12)
                val tt1 = ffj(j, a, b, c) + d + ss2 + w[j + 68]
                val tt2 = ggj(j, e, f, g) + h + ss1 + w[j]
                d = c
                c = rotl(b, 9)
                b = a
                a = tt1
                h = g
                g = rotl(f, 19)
                f = e
                e = tt2 xor rotl(tt2, 9) xor rotl(tt2, 17)
            }
            reg[0] = reg[0] xor a; reg[1] = reg[1] xor b; reg[2] = reg[2] xor c; reg[3] = reg[3] xor d
            reg[4] = reg[4] xor e; reg[5] = reg[5] xor f; reg[6] = reg[6] xor g; reg[7] = reg[7] xor h
        }

        val out = IntArray(32)
        for (i in 0 until 8) {
            out[i * 4] = (reg[i] ushr 24) and 0xFF
            out[i * 4 + 1] = (reg[i] ushr 16) and 0xFF
            out[i * 4 + 2] = (reg[i] ushr 8) and 0xFF
            out[i * 4 + 3] = reg[i] and 0xFF
        }
        return out
    }

    // ----------------------------------------------------------------- custom base64
    private fun getLongInt(roundNum: Int, longStr: IntArray): Int {
        val r = roundNum * 3
        val c1 = if (r < longStr.size) longStr[r] and 0xFF else 0
        val c2 = if (r + 1 < longStr.size) longStr[r + 1] and 0xFF else 0
        val c3 = if (r + 2 < longStr.size) longStr[r + 2] and 0xFF else 0
        return (c1 shl 16) or (c2 shl 8) or c3
    }

    private val MASKS = intArrayOf(16515072, 258048, 4032, 63)
    private val SHIFTS = intArrayOf(18, 12, 6, 0)

    private fun resultEncrypt(longStr: IntArray, table: String): String {
        val enc = ENCODING_TABLES[table] ?: error("unknown table $table")
        val sb = StringBuilder()
        var roundNum = 0
        var longInt = getLongInt(0, longStr)
        val totalChars = ceil(longStr.size / 3.0 * 4.0).toInt()
        for (i in 0 until totalChars) {
            if (i / 4 != roundNum) {
                roundNum += 1
                longInt = getLongInt(roundNum, longStr)
            }
            val index = i % 4
            val charIndex = (longInt and MASKS[index]) ushr SHIFTS[index]
            sb.append(enc[charIndex])
        }
        return sb.toString()
    }

    // ----------------------------------------------------------------- random prefix
    private fun generRandom(randomNum: Int, option: IntArray): IntArray {
        val byte1 = randomNum and 255
        val byte2 = (randomNum shr 8) and 255
        return intArrayOf(
            (byte1 and 170) or (option[0] and 85),
            (byte1 and 85) or (option[0] and 170),
            (byte2 and 170) or (option[1] and 85),
            (byte2 and 85) or (option[1] and 170)
        )
    }

    private fun generateRandomStr(): IntArray {
        // Same fixed "random" values as the JS/py source.
        val out = ArrayList<Int>(12)
        out.addAll(generRandom((0.123456789 * 10000).toInt(), intArrayOf(3, 45)).toList())
        out.addAll(generRandom((0.987654321 * 10000).toInt(), intArrayOf(1, 0)).toList())
        out.addAll(generRandom((0.555555555 * 10000).toInt(), intArrayOf(1, 5)).toList())
        return out.toIntArray()
    }

    // ----------------------------------------------------------------- main body
    private fun utf8(s: String): IntArray {
        val bytes = s.toByteArray(Charsets.UTF_8)
        return IntArray(bytes.size) { bytes[it].toInt() and 0xFF }
    }

    private fun charCodes(s: String): IntArray = IntArray(s.length) { s[it].code }

    private fun splitBytes(num: Int): IntArray =
        intArrayOf((num ushr 24) and 255, (num ushr 16) and 255, (num ushr 8) and 255, num and 255)

    private fun generateRc4BbStr(
        urlSearchParams: String,
        userAgent: String,
        windowEnv: String,
        startTimeMs: Long,
        suffix: String = "cus",
        arguments: IntArray = intArrayOf(0, 1, 14)
    ): IntArray {
        val startTime = startTimeMs
        val endTime = startTime + 100

        val urlParamsList = sm3(sm3(utf8(urlSearchParams + suffix)))
        val cus = sm3(sm3(utf8(suffix)))
        val ua = sm3(utf8(resultEncrypt(rc4(charCodes(userAgent), intArrayOf(0, 1, 14)), "s3")))

        val b = IntArray(73)
        b[8] = 3
        b[18] = 44

        b[20] = ((startTime ushr 24) and 0xFF).toInt()
        b[21] = ((startTime ushr 16) and 0xFF).toInt()
        b[22] = ((startTime ushr 8) and 0xFF).toInt()
        b[23] = (startTime and 0xFF).toInt()
        b[24] = ((startTime ushr 32) and 0xFF).toInt()
        b[25] = ((startTime ushr 40) and 0xFF).toInt()

        val a0 = splitBytes(arguments[0])
        b[26] = a0[0]; b[27] = a0[1]; b[28] = a0[2]; b[29] = a0[3]
        b[30] = (arguments[1] / 256) and 255
        b[31] = (arguments[1] % 256) and 255
        val a1 = splitBytes(arguments[1]); b[32] = a1[0]; b[33] = a1[1]
        val a2 = splitBytes(arguments[2]); b[34] = a2[0]; b[35] = a2[1]; b[36] = a2[2]; b[37] = a2[3]

        b[38] = urlParamsList[21]; b[39] = urlParamsList[22]
        b[40] = cus[21]; b[41] = cus[22]
        b[42] = ua[23]; b[43] = ua[24]

        b[44] = ((endTime ushr 24) and 0xFF).toInt()
        b[45] = ((endTime ushr 16) and 0xFF).toInt()
        b[46] = ((endTime ushr 8) and 0xFF).toInt()
        b[47] = (endTime and 0xFF).toInt()
        b[48] = b[8]
        b[49] = ((endTime ushr 32) and 0xFF).toInt()
        b[50] = ((endTime ushr 40) and 0xFF).toInt()

        val pid = splitBytes(110624)
        b[52] = pid[0]; b[53] = pid[1]; b[54] = pid[2]; b[55] = pid[3]

        b[57] = 6383 and 255
        b[58] = (6383 ushr 8) and 255
        b[59] = (6383 ushr 16) and 255
        b[60] = (6383 ushr 24) and 255

        val wenv = charCodes(windowEnv)
        b[64] = wenv.size
        b[65] = b[64] and 255
        b[66] = (b[64] ushr 8) and 255
        b[69] = 0; b[70] = 0; b[71] = 0

        b[72] = b[18] xor b[20] xor b[26] xor b[30] xor b[38] xor b[40] xor b[42] xor b[21] xor b[27] xor b[31] xor
            b[35] xor b[39] xor b[41] xor b[43] xor b[22] xor b[28] xor b[32] xor b[36] xor b[23] xor b[29] xor
            b[33] xor b[37] xor b[44] xor b[45] xor b[46] xor b[47] xor b[48] xor b[49] xor b[50] xor b[24] xor
            b[25] xor b[52] xor b[53] xor b[54] xor b[55] xor b[57] xor b[58] xor b[59] xor b[60] xor b[65] xor
            b[66] xor b[70] xor b[71]

        val head = intArrayOf(
            b[18], b[20], b[52], b[26], b[30], b[34], b[58], b[38], b[40], b[53], b[42], b[21],
            b[27], b[54], b[55], b[31], b[35], b[57], b[39], b[41], b[43], b[22], b[28], b[32],
            b[60], b[36], b[23], b[29], b[33], b[37], b[44], b[45], b[59], b[46], b[47], b[48],
            b[49], b[50], b[24], b[25], b[65], b[66], b[70], b[71]
        )
        val bb = head + wenv + intArrayOf(b[72])
        return rc4(bb, intArrayOf(121))
    }

    // ----------------------------------------------------------------- test seams
    internal fun debugSm3Hex(s: String): String {
        val h = sm3(utf8(s))
        val sb = StringBuilder()
        for (v in h) sb.append("%02x".format(v))
        return sb.toString()
    }

    internal fun debugRc4(plain: String, key: String): IntArray = rc4(charCodes(plain), charCodes(key))
    internal fun debugResultEncrypt(s: String, table: String): String = resultEncrypt(charCodes(s), table)
    internal fun debugRandomStr(): IntArray = generateRandomStr()
    internal fun debugDoubleSm3(s: String): IntArray = sm3(sm3(utf8(s)))
    internal fun debugBbStr(q: String, ua: String, startTimeMs: Long): IntArray =
        generateRc4BbStr(q, ua, WINDOW_ENV, startTimeMs)
}
