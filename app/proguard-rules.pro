# Add project specific ProGuard rules here.
# =====================================================================
# 反逆向配置（release 已开启 isMinifyEnabled=true）
# =====================================================================

# ---- Android 组件：名称被 AndroidManifest 引用，必须保名 ----
-keep class com.oopnv70.simpleapp.MainActivity { *; }
-keep class com.oopnv70.simpleapp.LicenseOverlayService { *; }

# ---- JNI/反射桥接类：native 层以字符串形式引用，必须保名 ----
#   1) JNI_OnLoad 用 FindClass("com/oopnv70/simpleapp/License") 注册 nativeVerify
#   2) native 反射调用 fallbackCheck / legacyCheck 与 INSTANCE 字段
#   被混淆改名会导致 native 注册失败或探测误判 → 必须保名
-keep class com.oopnv70.simpleapp.License { *; }
-keepclassmembers class com.oopnv70.simpleapp.License {
    *** fallbackCheck(...);
    *** legacyCheck(...);
    *** nativeVerify(...);
    *** mk(...);
    *** ck(...);
    public static final com.oopnv70.simpleapp.License INSTANCE;
}

# ---- 移除日志：release 不输出任何 Log（防止运行时泄露线索） ----
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}

# ---- 不保留任何调试/源文件属性 ----
-renamesourcefileattribute
-keepattributes !SourceFile,!LineNumberTable,!LocalVariableTable,!LocalVariableTypeTable

# ---- 压缩优化强度拉满 ----
-optimizationpasses 5
-allowaccessmodification
# 注意：不要用 -repackageclasses —— 它会把类挪到默认包，
# 破坏 AndroidManifest 的组件名与 JNI FindClass("com/oopnv70/simpleapp/License") 路径。

# 说明：卡密相关内容不在此处；全部在 native 层，dex 无任何卡密痕迹。
