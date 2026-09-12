package com.oopnv70.simpleapp

/**
 * 卡密校验入口（纯转发壳）。
 *
 * 重要：真正的校验逻辑**全部在 native 层**（liblicense.so）：
 *   - 盐派生、明文拼装、10 万次迭代 SHA-256、反调试哨兵，都在 C++ 里。
 *   - 本类只是 JNI 入口，dex 中**不含**任何卡密、盐、摘要或比对逻辑。
 *
 * 对逆向者的影响：
 *   - 反编译 dex 只能看到一个 `external fun` 声明，改它没有任何意义
 *     （真正的逻辑不在 Java 层）。
 *   - 要改返回值必须逆向 ARM 汇编并绕过 native 的反调试哨兵。
 */
object License {

    init {
        // 加载 native 库；失败则视为不可用（避免直接崩溃）
        runCatching { System.loadLibrary("license") }
    }

    /** native 校验实现 */
    private external fun nativeVerify(input: String): Boolean

    /**
     * 校验输入卡密是否正确。
     * @return true 表示卡密有效
     */
    fun verify(input: String): Boolean {
        if (input.isEmpty()) return false
        return runCatching { nativeVerify(input) }.getOrDefault(false)
    }
}