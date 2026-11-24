# 连镜 Release keep 规则
# 目标：允许 R8 裁剪 Material 扩展图标与未用代码，同时保住 JNI、Gson、ACRA 和 Manifest 反射入口。

# JNI：liblianjingraw.so 用的是 Java_<包名>_<类名>_<方法名> 的静态注册，类名/方法名不能被混淆。
-keep class com.lutcamera.lutcamerapro.ui.douk.RawDecode { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Gson 反射：BorderStateRepository 只序列化 BorderState；模型包统一保留字段名。
-keep class com.lutcamera.lutcamerapro.model.** { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ACRA 通过反射读取配置/报告字段；缺失时会在启动早期静默失败。
-keep class org.acra.** { *; }
-keep class com.lutcamera.lutcamerapro.BuildConfig { *; }

# Haze 1.2.0 是 Kotlin 2.1 metadata，当前项目 Kotlin 1.9 仅读取不反射；保留公开 API 避免 Compose 调用点被裁。
-keep class dev.chrisbanes.haze.** { *; }

# 保留行号，Release 崩溃仍能定位到具体行；不保留局部变量名。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
