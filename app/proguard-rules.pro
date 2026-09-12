# Add project specific ProGuard rules here.
# 练习用途：默认不开启混淆（release: isMinifyEnabled=false）。
# 如果你想增加破解难度，可把 release 的 isMinifyEnabled 改为 true。
# 保留入口 Activity
-keep class com.oopnv70.simpleapp.MainActivity { *; }

# 保留 native 桥接类与诱饵方法名：
# native 层需要通过 JNI 反射调用 fallbackCheck / legacyCheck 与 INSTANCE 字段，
# 一旦被混淆改名，native 探测会失效（会误判为"方法不存在"）。
-keep class com.oopnv70.simpleapp.License { *; }
-keepclassmembers class com.oopnv70.simpleapp.License {
    *** fallbackCheck(...);
    *** legacyCheck(...);
    public static final com.oopnv70.simpleapp.License INSTANCE;
}
