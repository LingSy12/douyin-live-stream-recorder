package com.example.douyinautoliverecorder

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the Kotlin [ABogus] port byte-for-byte against reference vectors generated from the
 * Python original (ihmily/DouyinLiveRecorder src/ab_sign.py) with a fixed timestamp
 * (start_time = 1_700_000_000_000 ms).
 */
class ABogusTest {

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.5845.97 Safari/537.36"
    private val q1 = "aid=6383&app_name=douyin_web&device_platform=web&web_rid=123456789&msToken="
    private val q2 = "aid=6383&app_name=douyin_web&device_platform=web&web_rid=21306770991&browser_name=Chrome&msToken="
    private val fixedTime = 1_700_000_000_000L

    @Test
    fun sm3_standard_vectors() {
        assertEquals(
            "66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0",
            ABogus.debugSm3Hex("abc")
        )
        assertEquals(
            "1ab21d8355cfa17f8e61194831e81a8f22bec8c728fefb747ed035eb5082aa2b",
            ABogus.debugSm3Hex("")
        )
    }

    @Test
    fun doubleSm3_vector() {
        assertEquals(
            listOf(140, 104, 237, 63, 143, 96, 102, 117, 25, 218, 36, 213, 131, 91, 51, 188,
                149, 202, 44, 167, 235, 1, 6, 215, 178, 231, 36, 117, 202, 86, 29, 143),
            ABogus.debugDoubleSm3("x=1cus").toList()
        )
    }

    @Test
    fun rc4_vector() {
        assertEquals(
            listOf(99, 9, 88, 129, 75, 175, 12, 37, 58, 39, 108),
            ABogus.debugRc4("hello world", "key").toList()
        )
    }

    @Test
    fun resultEncrypt_vectors() {
        assertEquals("52Xu-2S", ABogus.debugResultEncrypt("hello", "s4"))
        assertEquals("54Xu+4S", ABogus.debugResultEncrypt("hello", "s3"))
    }

    @Test
    fun randomStr_vector() {
        assertEquals(
            listOf(131, 82, 5, 44, 129, 20, 34, 4, 163, 17, 5, 21),
            ABogus.debugRandomStr().toList()
        )
    }

    @Test
    fun bbStr_vector() {
        assertEquals(
            listOf(105, 75, 164, 66, 132, 231, 81, 204, 211, 161, 212, 150, 55, 116, 161, 60, 1,
                144, 100, 36, 199, 158, 68, 246, 220, 250, 0, 219, 248, 166, 88, 190, 81, 86, 231,
                48, 151, 147, 155, 91, 250, 48, 209, 145, 120, 169, 56, 131, 204, 167, 176, 61, 53,
                29, 221, 201, 96, 116, 34, 134, 32, 71, 1, 185, 246, 138, 105, 99, 112, 105, 56,
                115, 179, 70, 0, 203, 210, 47, 101, 28, 19, 242, 247, 200, 105, 88, 222, 130, 160,
                77, 211, 86, 179, 187, 141, 77, 114, 105, 255, 109, 21, 159, 169, 65, 155, 182, 99,
                78, 217, 92),
            ABogus.debugBbStr(q1, ua, fixedTime).toList()
        )
    }

    @Test
    fun fullSignature_vectors() {
        assertEquals(
            "E7mhBmg6mEVNgf6X56KLfY3q6Wr3YUVI0HViMD2f4d3ZqL39HMYD9exoIBGvXKWjwG/-IeYjy4hbO3xprQAjM36UHWwEUdQ2mgWkKl5Q5I0j53iruyRDntmF4vj3SFlm5XNAEOk0y75rKb70Woqe-vIlO62-zo0/9Xj=",
            ABogus.sign(q1, ua, fixedTime)
        )
        assertEquals(
            "E7mhBmg6mEVNgf6X56KLfY3q6Vl3YUVI0HViMD2fJV3ZqL39HMYD9exoIBGvXKWjwG/-IeYjy4hbO3xprQAjM36UHWwEUdQ2mgWkKl5Q5I0j53iruyRDntmF4vj3SFlm5XNAEOk0y75rKb70Woqe-vIlO62-zo0/9vR=",
            ABogus.sign(q2, ua, fixedTime)
        )
    }
}
