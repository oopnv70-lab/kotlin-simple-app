// license.cpp
// 卡密校验核心（Native 实现）
//
// 设计目标：静态与动态差距极大
//  - 静态（IDA/Ghidra 读 .so）：只能看到一组无意义的整数种子、一堆乱码字节、
//    以及一颗不可逆摘要；无法直接读出卡密，也无法一眼看出盐是什么。
//  - 动态：运行时才把盐派生出来、把明文拼装出来，然后做 10 万次迭代 SHA-256 比对。
//
// 防护点：
//  1. 盐（SALT）不落库，由 SEED 整数数组运行时派生。
//  2. 明文卡密不落库，由 ENC 字节数组与 MASK 异或后拼装。
//  3. 10 万次迭代哈希，拉高暴力枚举成本。
//  4. 反调试哨兵：检测 TracerPid / Frida，命中即让校验恒失败（温和）。
//  5. 双层校验：先验"程序自身未被篡改/未在调试"，再验输入。

#include <jni.h>
#include <cstdint>
#include <cstring>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <unistd.h>
#include <sys/types.h>
#include <android/log.h>

#include "sha256.h"

#define LOG_TAG "LicenseNative"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

// ======================= 机密素材（静态看到的是无意义数字/乱码） =======================

// 种子：用于运行时派生盐
static const uint32_t SEED[5] = { 0x5A49D3u, 0x2D37A1u, 0x9C11F0u, 0x40B75Eu, 0x1E93C2u };

// 明文卡密经"异或掩码"后的密文数组（长度 22）
static const uint8_t ENC[22] = {
    0x64,0x12,0x8C,0x5E,0xD5,0x67,0x50,0x76,0x98,0x21,0x58,
    0xDD,0x67,0xD9,0x6C,0xDB,0x1E,0xFD,0x4F,0x25,0x87,0x4B
};

// 掩码：与 ENC 异或还原明文
static const uint8_t MASK[22] = {
    0x37,0x5B,0xC1,0x0E,0x99,0x22,0x7D,0x44,0xA8,0x13,0x6E,
    0xF0,0x2C,0x81,0x55,0x9D,0x33,0xBA,0x07,0x6A,0xD4,0x1F
};

// 期望摘要（不可逆；迭代 100000 次）
static const uint8_t EXPECTED[32] = {
    0x7c,0xa6,0x47,0xa2,0x04,0xf2,0x7d,0x62,0x35,0x70,0x20,0xd9,0x09,0x5d,0x94,0x81,
    0xe0,0xe2,0x04,0xe5,0x4c,0xaf,0x02,0xb6,0xa0,0xd0,0x6b,0x3b,0xa9,0x25,0x1f,0xf4
};

// 迭代次数：10 万
static const int ITER = 100000;

// ======================= 运行时派生 =======================

// 由 SEED 派生盐（与设计稿严格一致；静态难以一眼还原）
void deriveSalt(uint8_t out[10]) {
    uint32_t acc = 0x9E3779B9u;
    int n = 0;
    for (int i = 0; i < 5; ++i) {
        acc = (acc << 5) | (acc >> 27);
        acc ^= (SEED[i] * 0x101u + (uint32_t)i * 0x7Fu);
        for (int j = 0; j < 2; ++j) {
            acc = (acc << 7) | (acc >> 25);
            out[n++] = (uint8_t)((acc >> (8 * ((i + j) % 4))) & 0xFF);
        }
    }
}

// 运行时拼装明文卡密（不落库）
void assembleKey(uint8_t out[22]) {
    for (int i = 0; i < 22; ++i) out[i] = (uint8_t)(ENC[i] ^ MASK[i]);
}

// ======================= 迭代哈希 =======================

// 对 data 做 ITER 次 SHA-256，输出 32 字节
void iterHash(const uint8_t* data, size_t len, uint8_t out[32]) {
    uint8_t h[32];
    lsha::digest(data, len, h);
    for (int i = 1; i < ITER; ++i) {
        uint8_t tmp[32];
        lsha::digest(h, 32, tmp);
        memcpy(h, tmp, 32);
    }
    memcpy(out, h, 32);
}

// 定长比较（防时序侧信道）
bool equals32(const uint8_t* a, const uint8_t* b) {
    uint8_t d = 0;
    for (int i = 0; i < 32; ++i) d |= (uint8_t)(a[i] ^ b[i]);
    return d == 0;
}

// ======================= 反调试 =======================

// 读取 /proc/self/status 中的 TracerPid
bool tracerPidNonZero() {
    FILE* f = fopen("/proc/self/status", "r");
    if (!f) return false;
    char line[256];
    bool traced = false;
    while (fgets(line, sizeof(line), f)) {
        if (strncmp(line, "TracerPid:", 10) == 0) {
            int pid = atoi(line + 10);
            if (pid != 0) traced = true;
            break;
        }
    }
    fclose(f);
    return traced;
}

// 粗略检测 Frida：扫描自身内存映射里是否含 frida/gadget 字样
bool fridaDetected() {
    FILE* f = fopen("/proc/self/maps", "r");
    if (!f) return false;
    char line[512];
    bool hit = false;
    while (fgets(line, sizeof(line), f)) {
        if (strstr(line, "frida") || strstr(line, "gadget") || strstr(line, "gum-js-loop")) {
            hit = true;
            break;
        }
    }
    fclose(f);
    return hit;
}

// 综合判定：是否处于被调试/被注入状态
// 注意：不使用 ptrace(PTRACE_TRACEME) —— 它有"一次性"副作用，
// 调用一次后进程自身即被标记为被追踪，后续再调用会一直失败，
// 导致校验永久误判。这里只用无副作用的只读探测。
bool tampered() {
    if (tracerPidNonZero()) return true;
    if (fridaDetected()) return true;
    return false;
}

// ======================= 诱饵（陷阱）探测 =======================
//
// 原理：
//   dex 里放置若干"假校验"函数，正常情况下【永远返回 false】。
//   本函数通过 JNI 反向调它们，看返回值：
//     - 返回 false → 正常（没人动过）
//     - 返回 true  → 说明攻击者把这些"永远不可能通过"的函数改成了恒 true
//                    → 判定为被篡改（蜜罐命中）
//
// 该方案优点：不依赖签名 / 文件哈希 / 内存扫描，纯逻辑自证，零误报。

// 探测单个诱饵方法：返回 true 表示"命中陷阱"
// 兼容两种编译形态：
//   - Kotlin object 的普通方法 → 实例方法（配合 INSTANCE 实例调用）
//   - 静态方法（若加过 @JvmStatic）→ 静态方法
bool probeBait(JNIEnv* env, jclass cls, jobject inst, const char* name) {
    const char* sig = "(Ljava/lang/String;)Z";

    jstring probe = env->NewStringUTF("PROBE-PROBE-PROBE-PROBE");
    if (probe == nullptr) {
        env->ExceptionClear();
        return false;
    }

    // 形态 1：实例方法
    jmethodID mid = env->GetMethodID(cls, name, sig);
    if (mid != nullptr) {
        jboolean r = env->CallBooleanMethod(inst, mid, probe);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return true; // 调用异常 → 视为被篡改
        }
        return r == JNI_TRUE; // 竟然返回 true = 被改过
    }
    env->ExceptionClear();

    // 形态 2：静态方法
    jmethodID smid = env->GetStaticMethodID(cls, name, sig);
    if (smid != nullptr) {
        jboolean r = env->CallStaticBooleanMethod(cls, smid, probe);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return true;
        }
        return r == JNI_TRUE;
    }
    env->ExceptionClear();

    // 两种形态都找不到 → 方法被删除/改名，视为被篡改
    return true;
}

// 综合探测所有诱饵
bool baitsTriggered(JNIEnv* env) {
    jclass cls = env->FindClass("com/oopnv70/simpleapp/License");
    if (cls == nullptr) {
        env->ExceptionClear();
        return true; // 类都没了，肯定被改过
    }
    // 取单例实例：License.INSTANCE
    jfieldID instField = env->GetStaticFieldID(cls, "INSTANCE",
                                               "Lcom/oopnv70/simpleapp/License;");
    if (instField == nullptr) {
        env->ExceptionClear();
        return true; // 单例结构被破坏 → 视为被篡改
    }
    jobject inst = env->GetStaticObjectField(cls, instField);
    if (inst == nullptr) {
        env->ExceptionClear();
        return true;
    }

    const char* names[] = {"fallbackCheck", "legacyCheck"};
    bool hit = false;
    for (const char* n : names) {
        if (probeBait(env, cls, inst, n)) { hit = true; break; }
    }
    return hit;
}

} // namespace

// ======================= JNI 导出 =======================

// 返回码约定（jint）：
//   0 = 验证失败
//   1 = 验证通过
//   2 = 检测到篡改（蜜罐命中）—— dex 层据此弹出"你被骗了"
// 注意：因 CMake 开了 -fvisibility=hidden，JNI 入口必须显式导出，否则运行时找不到。
//
// 【反逆向】不再使用 Java_com_xxx_nativeVerify 这种"可读符号"，改走 RegisterNatives：
//   - 导出符号表中只剩下 JNI_OnLoad 一项（Android 加载器硬性要求）
//   - 真实校验函数改成内部静态名（甚至无语义名），不进入 .dynsym
//   - 反编译者 nm/readelf 看不到任何有意义的函数名
static jint nv_impl(JNIEnv* env, jobject /*thiz*/, jstring input) {
    if (input == nullptr) return 0;

    // ===== 优先探测：诱饵陷阱（dex 是否被改） =====
    if (baitsTriggered(env)) {
        LOGW("bait triggered: dex tampered -> TAMPERED");
        return 2;
    }

    const char* raw = env->GetStringUTFChars(input, nullptr);
    if (raw == nullptr) return 0;
    std::string s(raw);
    env->ReleaseStringUTFChars(input, raw);

    if (s.empty()) return 0;

    // 反调试哨兵：命中则视为篡改
    if (tampered()) {
        LOGW("tampered/debug environment detected -> TAMPERED");
        return 2;
    }

    // 派生盐 + 拼装明文
    uint8_t salt[10];
    deriveSalt(salt);
    uint8_t key[22];
    assembleKey(key);

    // 双层校验：
    //  A) 输入 == 明文卡密 的迭代摘要 必须等于 EXPECTED
    //  B) 程序自身还原出的明文 的迭代摘要 也必须等于 EXPECTED（自校验）
    //     —— 若有人篡改了 ENC/MASK/EXPECTED，这里会不一致，直接拒绝。
    uint8_t selfHash[32];
    {
        uint8_t buf[10 + 22];
        memcpy(buf, salt, 10);
        memcpy(buf + 10, key, 22);
        iterHash(buf, sizeof(buf), selfHash);
    }
    if (!equals32(selfHash, EXPECTED)) {
        LOGW("self-check failed (constants tampered?) -> TAMPERED");
        return 2;
    }

    // 校验用户输入
    uint8_t inHash[32];
    {
        size_t n = s.size();
        uint8_t* buf = (uint8_t*)malloc(10 + n);
        if (!buf) return 0;
        memcpy(buf, salt, 10);
        memcpy(buf + 10, s.data(), n);
        iterHash(buf, 10 + n, inHash);
        free(buf);
    }

    // 清零敏感内存
    memset(key, 0, sizeof(key));
    memset(salt, 0, sizeof(salt));

    return equals32(inHash, EXPECTED) ? 1 : 0;
}

// ======================= 动态注册（隐藏符号） =======================
// 【反逆向核心】用 RegisterNatives 注册，JNI 真实函数名不出现在符号表。
//
// 注意：类名/方法名是以字符串形式出现的（不是符号），strip 不会删字符串；
// 因此这里的字符串仍然有价值 —— 但它不是"符号表"，只是普通 .rodata 字符串。
// 若需要进一步弱化，可在构建期对这些字符串做异或编码（后续步骤）。
//
// 注册目标类：com/oopnv70/simpleapp/License
// 注册方法：nativeVerify(String) -> Int
//
// 若类被构建期重命名，这里需要同步；因此反逆向增强脚本会替换本字符串。
extern "C" __attribute__((visibility("default"))) JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    // ---- 动态注册：nativeVerify ----
    jclass cls = env->FindClass("com/oopnv70/simpleapp/License");
    if (cls == nullptr) {
        env->ExceptionClear();
        return JNI_VERSION_1_6;  // 找不到类也不崩，交给后续调用报错
    }

    static const JNINativeMethod methods[] = {
        // 方法名、签名、函数指针
        { const_cast<char*>("nativeVerify"),
          const_cast<char*>("(Ljava/lang/String;)I"),
          reinterpret_cast<void*>(nv_impl) },
    };
    if (env->RegisterNatives(cls, methods, sizeof(methods) / sizeof(methods[0])) != JNI_OK) {
        env->ExceptionClear();
    }
    env->DeleteLocalRef(cls);
    return JNI_VERSION_1_6;
}