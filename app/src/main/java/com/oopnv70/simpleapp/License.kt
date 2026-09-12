package com.oopnv70.simpleapp

import android.util.Base64
import java.security.MessageDigest

/**
 * 卡密校验核心。
 *
 * 设计说明（给练习者的提示，反编译时不必看这行）：
 *  - 卡密不以明文出现，以 Base64 编码后的字节序列存放。
 *  - 校验时先 Base64 解码，再与输入做比较。
 *  - 为了增加一点点门槛，解码后还会做一次 SHA-256 摘要比对，
 *    避免"直接搜字符串"就能找到。
 */
object License {

    /**
     * 分段存储的 Base64 片段（解码后拼接）。
     * 提示：单独看任何一段都得不到完整卡密。
     */
    private val PART_A: String = "U0lNUExFLTIwMjYt"
    private val PART_B: String = "S1g5Ri1HSE9TVA=="

    /** 组合后 Base64 解码 -> 原始卡密的字节 */
    private fun rawKey(): ByteArray {
        val encoded = PART_A + PART_B
        return Base64.decode(encoded, Base64.DEFAULT)
    }

    /** 明文卡密（仅内存中出现，来自 Base64 解码） */
    fun plainKey(): String = String(rawKey(), Charsets.UTF_8)

    /** SHA-256 摘要，用于实际比对 */
    private fun digest(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** 期望的摘要值（正确卡密的 SHA-256） */
    private val EXPECTED: String = "3c6d60fe2605508fde00b1da413ba3a85e607f08b9d0452ccf258a9c84be0fdd"

    /**
     * 校验输入卡密是否正确。
     * @return true 表示卡密有效
     */
    fun verify(input: String): Boolean {
        if (input.isEmpty()) return false
        // 长度粗筛，避免明显错误的输入走到摘要计算
        if (input.length != plainKey().length) return false
        // 与预存摘要比对（同时也与实时计算结果比对，双保险）
        val d = digest(input)
        return d == EXPECTED && d == digest(plainKey())
    }
}
