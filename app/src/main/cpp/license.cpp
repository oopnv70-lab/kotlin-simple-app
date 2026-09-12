// a.cpp

#include <jni.h>
#include <cstdint>
#include <cstring>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <unistd.h>
#include <sys/types.h>

#include "d.h"

namespace {

// ---------- 字符串隐藏 ----------
// 源码中不出现任何字符串字面量：所有敏感字符串以【纯数字数组】形式存放，
// 运行时用固定密钥流异或还原到栈上，用完清零。
// 编译器只能看到一堆 uint32，不会在 .rodata 里留下原文，strings 也搜不到。
struct SB {
    const uint32_t* d;   // 加密后的 4 字节块
    unsigned n;          // 有效字节数
};

__attribute__((noinline))
static void dec(const SB& s, char* out) {
    unsigned k = 0x9E3779B9u ^ (s.n * 2654435761u);
    unsigned char* o = (unsigned char*)out;
    for (unsigned i = 0; i < s.n; ++i) {
        k = k * 131u + 7u;
        unsigned char c = (unsigned char)((s.d[i >> 2] >> ((i & 3) * 8)) & 0xFF);
        o[i] = (char)(c ^ (unsigned char)((k >> 3) & 0xFF));
    }
    o[s.n] = 0;
}

#define SD(name, cnt) do { dec(name, _sbuf); } while(0)

// 解密到 std::string（用完即清）
__attribute__((noinline))
static std::string dstr(const SB& s) {
    char b[256];
    dec(s, b);
    std::string r(b, s.n);
    for (unsigned i = 0; i < sizeof(b); ++i) b[i] = 0;
    return r;
}

// 解密到调用方 buffer
#define QB2(s, buf) dec(s, buf)

// ---------- 字符串表（纯数字，源码中无字符串字面量） ----------
static const uint32_t S_CLASS_d[] = {0x0e57d1e7u,0xf2934e4au,0xa86c4360u,0x92c8de24u,0xdddf8f84u,0x81eb22b9u,0xe00e4599u,0x0000001eu};
static const SB S_CLASS = {S_CLASS_d, 29};
static const uint32_t S_NV_d[] = {0x7ad266e2u,0x9d4b617du,0x14a27848u};
static const SB S_NV = {S_NV_d, 12};
static const uint32_t S_SIG_d[] = {0xc38f3bbfu,0x01d1cb5eu,0x8780a368u,0x3ad29469u,0x471284d5u,0x000000c5u};
static const SB S_SIG = {S_SIG_d, 21};
static const uint32_t S_INST_d[] = {0x67a3c4eau,0xb5ecd16bu};
static const SB S_INST = {S_INST_d, 8};
static const uint32_t S_INSTSIG_d[] = {0xf48227ddu,0x589d7481u,0x57b054b5u,0x3bc52a37u,0x9404ac15u,0x088927b2u,0x2d1e7d46u,0x00db70dfu};
static const SB S_INSTSIG = {S_INSTSIG_d, 31};
static const uint32_t S_FB_d[] = {0x5f2ca1bcu,0x25aaa239u,0xba47de6fu,0x00000026u};
static const SB S_FB = {S_FB_d, 13};
static const uint32_t S_LG_d[] = {0x5a69dc21u,0x2a783131u,0x0013e482u};
static const SB S_LG = {S_LG_d, 11};
static const uint32_t S_PROBE_d[] = {0x58d7aff4u,0xab5d89f4u,0xa5573981u,0x85e8d0abu,0xe49c947du,0x00c462cau};
static const SB S_PROBE = {S_PROBE_d, 23};
static const uint32_t S_FBSIG_d[] = {0xc38f3bbfu,0x01d1cb5eu,0x8780a368u,0x3ad29469u,0x471284d5u,0x000000d6u};
static const SB S_FBSIG = {S_FBSIG_d, 21};
static const uint32_t S_STATUS_d[] = {0x0d62eaa2u,0x6002ca45u,0xeb0dc6e3u,0x6e57aabcu,0x000000a2u};
static const SB S_STATUS = {S_STATUS_d, 17};
static const uint32_t S_TPID_d[] = {0xc8c262e4u,0x15eeebd6u,0x0000c8c2u};
static const SB S_TPID = {S_TPID_d, 10};
static const uint32_t S_MAPS_d[] = {0xf431c698u,0xaf5b02d7u,0xc4b2b2adu,0x00d1dbbfu};
static const SB S_MAPS = {S_MAPS_d, 15};
static const uint32_t S_FRIDA_d[] = {0x50028b0bu,0x000000bfu};
static const SB S_FRIDA = {S_FRIDA_d, 5};
static const uint32_t S_GADGET_d[] = {0x8bc0472bu,0x00001352u};
static const SB S_GADGET = {S_GADGET_d, 6};
static const uint32_t S_GUM_d[] = {0x1663cc2au,0x2e163b38u,0x0008e888u};
static const SB S_GUM = {S_GUM_d, 11};

// ---------- 素材 ----------
static const uint32_t P[5] = { 0x5A49D3u, 0x2D37A1u, 0x9C11F0u, 0x40B75Eu, 0x1E93C2u };

static const uint8_t E[22] = {
    0x64,0x12,0x8C,0x5E,0xD5,0x67,0x50,0x76,0x98,0x21,0x58,
    0xDD,0x67,0xD9,0x6C,0xDB,0x1E,0xFD,0x4F,0x25,0x87,0x4B
};

static const uint8_t M[22] = {
    0x37,0x5B,0xC1,0x0E,0x99,0x22,0x7D,0x44,0xA8,0x13,0x6E,
    0xF0,0x2C,0x81,0x55,0x9D,0x33,0xBA,0x07,0x6A,0xD4,0x1F
};

static const uint8_t V[32] = {
    0x7c,0xa6,0x47,0xa2,0x04,0xf2,0x7d,0x62,0x35,0x70,0x20,0xd9,0x09,0x5d,0x94,0x81,
    0xe0,0xe2,0x04,0xe5,0x4c,0xaf,0x02,0xb6,0xa0,0xd0,0x6b,0x3b,0xa9,0x25,0x1f,0xf4
};

static const int R = 100000;

// ---------- 派生 ----------
void g1(uint8_t* o) {
    volatile uint32_t a = 0x9E3779B9u;
    int n = 0;
    for (int i = 0; i < 5; ++i) {
        a = (a << 5) | (a >> 27);
        a ^= (P[i] * 0x101u + (uint32_t)i * 0x7Fu);
        for (int j = 0; j < 2; ++j) {
            a = (a << 7) | (a >> 25);
            o[n++] = (uint8_t)((a >> (8 * ((i + j) % 4))) & 0xFF);
        }
    }
}

void g2(uint8_t* o) {
    // volatile 阻止编译器在编译期折叠 E^M（否则明文会被写进 .rodata！）
    for (int i = 0; i < 22; ++i) {
        volatile uint8_t a = E[i];
        volatile uint8_t b = M[i];
        o[i] = (uint8_t)(a ^ b);
    }
}

void g3(const uint8_t* d, size_t n, uint8_t* o) {
    uint8_t h[32];
    ls::f(d, n, h);
    for (int i = 1; i < R; ++i) {
        uint8_t t[32];
        ls::f(h, 32, t);
        memcpy(h, t, 32);
    }
    memcpy(o, h, 32);
}

bool g4(const uint8_t* a, const uint8_t* b) {
    uint8_t d = 0;
    for (int i = 0; i < 32; ++i) d |= (uint8_t)(a[i] ^ b[i]);
    return d == 0;
}

// ---------- 环境探测 ----------
bool h1() {
    char p[32];
    dec(S_STATUS, p);
    FILE* f = fopen(p, "r");
    for (unsigned i = 0; i < sizeof(p); ++i) p[i] = 0;
    if (!f) return false;
    char l[256];
    bool t = false;
    char k[24];
    dec(S_TPID, k);
    while (fgets(l, sizeof(l), f)) {
        if (strncmp(l, k, 10) == 0) {
            int v = atoi(l + 10);
            if (v != 0) t = true;
            break;
        }
    }
    fclose(f);
    for (unsigned i = 0; i < sizeof(k); ++i) k[i] = 0;
    return t;
}

bool h2() {
    char p[32];
    dec(S_MAPS, p);
    FILE* f = fopen(p, "r");
    for (unsigned i = 0; i < sizeof(p); ++i) p[i] = 0;
    if (!f) return false;
    char l[512];
    bool hit = false;
    char k1[16]; dec(S_FRIDA, k1);
    char k2[16]; dec(S_GADGET, k2);
    char k3[24]; dec(S_GUM, k3);
    while (fgets(l, sizeof(l), f)) {
        if (strstr(l, k1) || strstr(l, k2) || strstr(l, k3)) { hit = true; break; }
    }
    fclose(f);
    for (unsigned i = 0; i < sizeof(k1); ++i) k1[i] = 0;
    for (unsigned i = 0; i < sizeof(k2); ++i) k2[i] = 0;
    for (unsigned i = 0; i < sizeof(k3); ++i) k3[i] = 0;
    return hit;
}

bool h3() {
    if (h1()) return true;
    if (h2()) return true;
    return false;
}

// ---------- 探测 ----------
bool k1(JNIEnv* e, jclass c, jobject o, const std::string& nm) {
    std::string sg = dstr(S_FBSIG);
    std::string pv = dstr(S_PROBE);

    jstring js = e->NewStringUTF(pv.c_str());
    if (js == nullptr) { e->ExceptionClear(); return false; }

    jmethodID id = e->GetMethodID(c, nm.c_str(), sg.c_str());
    if (id != nullptr) {
        jboolean r = e->CallBooleanMethod(o, id, js);
        if (e->ExceptionCheck()) { e->ExceptionClear(); return true; }
        return r == JNI_TRUE;
    }
    e->ExceptionClear();

    jmethodID si = e->GetStaticMethodID(c, nm.c_str(), sg.c_str());
    if (si != nullptr) {
        jboolean r = e->CallStaticBooleanMethod(c, si, js);
        if (e->ExceptionCheck()) { e->ExceptionClear(); return true; }
        return r == JNI_TRUE;
    }
    e->ExceptionClear();
    return true;
}

bool k2(JNIEnv* e) {
    std::string cn = dstr(S_CLASS);
    std::string fn = dstr(S_INST);
    std::string fs = dstr(S_INSTSIG);

    jclass c = e->FindClass(cn.c_str());
    if (c == nullptr) { e->ExceptionClear(); return true; }

    jfieldID fi = e->GetStaticFieldID(c, fn.c_str(), fs.c_str());
    if (fi == nullptr) { e->ExceptionClear(); return true; }

    jobject ins = e->GetStaticObjectField(c, fi);
    if (ins == nullptr) { e->ExceptionClear(); return true; }

    std::string n1 = dstr(S_FB);
    std::string n2 = dstr(S_LG);
    if (k1(e, c, ins, n1)) return true;
    if (k1(e, c, ins, n2)) return true;
    return false;
}

// ---------- 静默终止 ----------
// 发现任何不一致时调用：不返回任何值，不抛异常，不弹窗，不写日志。
// 直接以正常退出码结束当前进程，外部看起来与"用户自己关闭了 APP"无法区分。
__attribute__((noinline, noreturn))
static void kk() {
    for (volatile unsigned long i = 0; i < 4; ++i) { }
    _exit(0);
}

// ---------- 放行前的最终复检 ----------
// 只有当主流程判定"通过"时才走到这里：再独立核对一遍环境与调用方状态。
// 任意一项不符 → 静默终止进程，绝不返回。
__attribute__((noinline))
static void zz(JNIEnv* e, const uint8_t* ih) {
    uint8_t c[32];
    memcpy(c, ih, 32);

    if (h3()) kk();

    uint8_t d[32];
    g3(c, 32, d);
    if (!g4(d, V)) kk();

    if (k2(e)) kk();

    for (unsigned i = 0; i < 32; ++i) c[i] = 0;
    for (unsigned i = 0; i < 32; ++i) d[i] = 0;
}

} // namespace

static jint nv(JNIEnv* env, jobject, jstring input) {
    if (input == nullptr) return 0;

    if (k2(env)) return 2;

    const char* raw = env->GetStringUTFChars(input, nullptr);
    if (raw == nullptr) return 0;
    std::string s(raw);
    env->ReleaseStringUTFChars(input, raw);

    if (s.empty()) return 0;

    if (h3()) return 2;

    uint8_t salt[10];
    g1(salt);
    uint8_t key[22];
    g2(key);

    uint8_t sh[32];
    {
        uint8_t b[32];
        memcpy(b, salt, 10);
        memcpy(b + 10, key, 22);
        g3(b, sizeof(b), sh);
    }
    if (!g4(sh, V)) return 2;

    uint8_t ih[32];
    {
        size_t n = s.size();
        uint8_t* b = (uint8_t*)malloc(10 + n);
        if (!b) return 0;
        memcpy(b, salt, 10);
        memcpy(b + 10, s.data(), n);
        g3(b, 10 + n, ih);
        free(b);
    }

    memset(key, 0, sizeof(key));
    memset(salt, 0, sizeof(salt));

    if (!g4(ih, V)) return 0;

    zz(env, ih);
    return 1;
}

extern "C" __attribute__((visibility("default"))) JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    std::string cn = dstr(S_CLASS);
    std::string mn = dstr(S_NV);
    std::string ms = dstr(S_SIG);

    jclass c = env->FindClass(cn.c_str());
    if (c == nullptr) {
        env->ExceptionClear();
        return JNI_VERSION_1_6;
    }

    static const JNINativeMethod m[] = {
        { const_cast<char*>(mn.c_str()),
          const_cast<char*>(ms.c_str()),
          reinterpret_cast<void*>(nv) },
    };
    if (env->RegisterNatives(c, m, 1) != JNI_OK) {
        env->ExceptionClear();
    }
    env->DeleteLocalRef(c);
    return JNI_VERSION_1_6;
}