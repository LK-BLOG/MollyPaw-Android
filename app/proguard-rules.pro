# 远程互联客户端的混淆规则。
# 目前 release 未开混淆（isMinifyEnabled = false），这份先占位并在
# 开启混淆时能直接生效。

# kotlinx-serialization：序列化类必须保留
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.mollypaw.remote.** {
    *** Companion;
}
-keepclasseswithmembers class com.mollypaw.remote.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# BouncyCastle（X25519 用）
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
