package com.oopnv70.simpleapp

import java.security.MessageDigest

/**
 * 卡密校验入口。
 *
 * 真实校验逻辑【全部在 native 层】（liblicense.so）：
 *   - 盐派生、明文拼装、10 万次迭代 SHA-256、反调试哨兵，都在 C++ 里。
 *
 * 本类另外放置了两个【诱饵函数】（fallbackCheck / legacyCheck）：
 *   - 它们看起来像"本地二次校验"，正常情况永远返回 false。
 *   - native 层会反向调用它们做探测：一旦它们能返回 true，
 *     就说明 dex 被人改过（蜜罐命中），native 会回传 TAMPERED 状态。
 *   - 所以：想通过"改 dex"来破解的人，会掉进这个陷阱。
 *
 * 返回码（nativeVerify）：
 *   0 = 验证失败
 *   1 = 验证通过
 *   2 = 检测到篡改（蜜罐命中）
 */
object License {

    /** native 校验结果：通过 */
    const val R_OK = 1

    /** native 校验结果：篡改命中 */
    const val R_TAMPERED = 2

    init {
        // 加载 native 库；失败则视为不可用（避免直接崩溃）
        runCatching { System.loadLibrary("n") }
    }

    /** native 校验实现，返回 0/1/2 三态 */
    private external fun nativeVerify(input: String): Int

    /** native 生成本机授权凭据（十六进制文本） */
    private external fun mk(): String?

    /** native 校验外部保存的凭据 */
    private external fun ck(token: String): Int

    // =====================================================================
    // 诱饵①：伪装成"本地兜底校验"，正常永远返回 false
    //  - 反编译者看到它，会以为"把它改成恒 true 就能绕过校验"
    //  - 一旦这么改，native 探测到它返回 true → 判定 dex 被篡改
    // =====================================================================
    @Suppress("unused")
    private fun fallbackCheck(key: String): Boolean {
        // 看起来像"卡密格式校验"——注意：这里的前缀是【假线索】，
        // 真卡密的前缀与此不同。反编译者若据此格式猜卡密，会被引到错误方向。
        val parts = key.split("-")
        if (parts.size != 4) return false
        if (parts[0] != "DEMO") return false
        if (parts[1] != "2024") return false
        // 看起来像"把后两段拼起来做摘要比对"
        val tail = parts[2] + parts[3]
        val md = MessageDigest.getInstance("SHA-256")
        val hex = md.digest(tail.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        // 这里的期望值【故意置零】，永远不可能相等 → 恒 false
        return hex == "0000000000000000000000000000000000000000000000000000000000000000"
    }

    // =====================================================================
    // 诱饵②：伪装成"旧版卡密兼容校验"，正常永远返回 false
    //  - 反编译者会想"删掉那个 if 判断 / 改成 return true 就行"
    //  - 一旦这么改，native 探测到它返回 true → 判定 dex 被篡改
    // =====================================================================
    @Suppress("unused")
    private fun legacyCheck(key: String): Boolean {
        // 看起来像"旧版卡密加密后的常量"
        val expected = intArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88)
        val buf = key.toByteArray(Charsets.UTF_8)
        if (buf.size < expected.size) return false

        // 看起来像"异或解密后逐字节比对"
        for (i in expected.indices) {
            val dec = buf[i].toInt() xor 0x5A
            if (dec != expected[i]) return false
        }
        return true
    }

    /**
     * 校验输入卡密。
     *
     * 表面上：native 校验 + 本地兜底校验（fallbackCheck / legacyCheck）
     * 实际：结果只由 native 决定；本地兜底永远返回 false，仅为诱饵。
     *
     * @return native 返回码：0 失败 / 1 通过 / 2 篡改
     */
    fun verifyCode(input: String): Int {
        if (input.isEmpty()) return 0
        val native = runCatching { nativeVerify(input) }.getOrDefault(0)
        // 诱饵：看起来像"本地二次校验"，实际永远不成立（避免被优化掉/被一眼看穿）
        if (native != R_OK && (fallbackCheck(input) || legacyCheck(input))) {
            // 不可能走到这里（两个诱饵恒 false）；一旦走到，说明被改过
            return R_TAMPERED
        }
        return native
    }

    /** 兼容旧调用：仅关心"是否通过"时使用（篡改视为未通过） */
    fun verify(input: String): Boolean = verifyCode(input) == R_OK

    // =====================================================================
    // 本机状态持久化
    //  - 按 Android 通用做法：优先使用外部私有目录
    //    （Android/data/<包名>/files），不可用时回退内部私有目录（filesDir）
    //  - 内容为 native 生成的凭据（非明文、非布尔值），文件名中性化
    // =====================================================================

    private const val NAME = "c"

    /** 候选目录：外部私有目录优先，内部私有目录兜底（顺序即优先级） */
    private fun dirs(ctx: android.content.Context): List<java.io.File> {
        val out = ArrayList<java.io.File>(2)
        runCatching { ctx.getExternalFilesDir(null) }.getOrNull()?.let { out.add(it) }
        runCatching { ctx.filesDir }.getOrNull()?.let { out.add(it) }
        return out
    }

    /** 找到已存在的凭据文件；找不到返回 null */
    private fun find(ctx: android.content.Context): java.io.File? {
        for (d in dirs(ctx)) {
            val f = java.io.File(d, NAME)
            if (f.isFile) return f
        }
        return null
    }

    /** 保存本机授权凭据；返回是否成功 */
    fun save(ctx: android.content.Context): Boolean {
        val token = runCatching { mk() }.getOrNull() ?: return false
        val all = dirs(ctx)
        for (d in all) {
            val ok = runCatching {
                if (!d.isDirectory) d.mkdirs()
                if (!d.isDirectory) return@runCatching false
                java.io.File(d, NAME).writeText(token)
                true
            }.getOrDefault(false)
            if (ok) {
                // 写入成功后，清掉其余目录里的同名旧文件，避免多份内容并存
                for (other in all) {
                    if (other != d) runCatching { java.io.File(other, NAME).delete() }
                }
                return true
            }
        }
        return false
    }

    /** 读取并校验本机授权凭据 */
    fun restore(ctx: android.content.Context): Boolean {
        val f = find(ctx) ?: return false
        val token = runCatching { f.readText() }.getOrNull() ?: return false
        if (token.isEmpty()) return false
        return runCatching { ck(token) == 1 }.getOrDefault(false)
    }

    /** 清除已保存的凭据（用于调试/重置；不影响其他状态） */
    fun clear(ctx: android.content.Context): Boolean {
        var removed = false
        for (d in dirs(ctx)) {
            runCatching {
                val f = java.io.File(d, NAME)
                if (f.exists() && f.delete()) removed = true
            }
        }
        return removed
    }
}