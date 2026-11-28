import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

// Release 签名配置从仓库外的 signing/ 目录读取；仓库（含根目录）不再保留任何明文口令。
// 查找顺序：环境变量 LENSLINK_SIGNING_DIR → 仓库上一级的 signing/ → 仓库内的 signing/（仅兼容旧布局）
// 目录内优先取 keystore.properties，否则取最新的 keystore.properties-YYYYMMDD
val signingDirs = listOfNotNull(
    System.getenv("LENSLINK_SIGNING_DIR")?.let { File(it) },
    rootProject.projectDir.parentFile?.resolve("signing"),
    rootProject.projectDir.resolve("signing")
)
val keystorePropsFile: File? = signingDirs
    .filter { it.isDirectory }
    .mapNotNull { dir ->
        dir.resolve("keystore.properties").takeIf { it.isFile }
            ?: dir.listFiles { f -> f.isFile && f.name.startsWith("keystore.properties-") }
                ?.maxByOrNull { it.lastModified() }
    }
    .firstOrNull()

val keystoreProps = Properties().apply {
    if (keystorePropsFile != null) {
        FileInputStream(keystorePropsFile).use { load(it) }
    }
}

fun signingProp(name: String): String =
    keystoreProps.getProperty(name)?.takeIf { it.isNotBlank() }
        ?: throw GradleException(
            "缺少签名配置 $name：release 包必须使用固定的连镜签名密钥。" +
                "请在 signing/keystore.properties 中提供（已查找：${signingDirs.joinToString("、")}）。"
        )

android {
    namespace = "com.lutcamera.lutcamerapro"
    // 回退到 34，与你的 Gradle 插件 8.5.1 完美匹配
    compileSdk = 35
    // LibRaw master 用 NDK r28 的 clang 编译；本机 SDK 里固定使用这个版本。
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.lutcamera.lutcamerapro"
        minSdk = 29
        // 回退到 34
        targetSdk = 34
        versionCode = 144
        versionName = "1.3.0"

        // 手机端只打包 arm64；LibRaw 静态库按 ABI 单独编译，减少构建时间和包体。
        ndk {
            abiFilters.add("arm64-v8a")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file(signingProp("storeFile"))
            storePassword = signingProp("storePassword")
            keyAlias = signingProp("keyAlias")
            keyPassword = signingProp("keyPassword")
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        debug {
            // 调试包单独 applicationId，可和已安装的 release 共存，方便 RAW 真机探针。
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // 48.7MB 里 46.7MB 是 dex；R8 + 资源收缩是包体最直接的一刀。
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        // ACRA 需要读取 BuildConfig 里的版本号/包名
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Haze 1.5.4 会把 androidx.activity 抬到 1.10.1（要求 compileSdk 35）。
    // 本项目仍用 AGP 8.5.1 + compileSdk 34，Haze 代码本身没有用到 1.10 的 API，
    // 所以把 activity 三个构件锁回项目原有的 1.9.0，避免为了模糊去改整条构建链。
    configurations.all {
        resolutionStrategy {
            force(
                "androidx.activity:activity:1.9.0",
                "androidx.activity:activity-ktx:1.9.0",
                "androidx.activity:activity-compose:1.9.0"
            )
        }
    }
    implementation("com.google.code.gson:gson:2.10.1")
    // 日志门面 + 文件日志 + 崩溃收集 + 网络日志
    implementation("com.jakewharton.timber:timber:5.0.1")
    implementation("com.elvishew:xlog:1.11.0")
    implementation("ch.acra:acra-core:5.11.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    // 真 RAW 解码：LibRaw 0.23.0 Snapshot202611 以源码编进 APK，
    // 带 nikonhe.cpp，可解尼康 Z50II 的 HE/HE★ NEF；不再用预编译的 AndroidLibRaw 0.21.2。
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    // 引入完整的 Material 扩展图标库
    implementation("androidx.compose.material:material-icons-extended")
    // 弃用可能包含超前 Alpha 版本的 libs.xxx，直接指定 100% 兼容 API 34 的稳定版
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    // collectAsStateWithLifecycle：页面不在前台时停收 StateFlow，省电也少无用重组
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.activity:activity-compose:1.9.0")

    // 使用稳定的 Compose BOM，统一锁定 UI 库的稳定版本
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // 真正的背景高斯模糊：Haze 用 RenderNode + RenderEffect 把卡片/底栏后面的内容模糊，
    // 不是靠半透明+渐变模拟。0.6.1 对应 Compose 1.6（当前 BOM 2024.04.01）。
    implementation("dev.chrisbanes.haze:haze:1.2.0")

    // 我们新增的页面导航依赖
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // 图库缩略图与细节查看。
    // Telephoto 0.13.0 的 zoomable-image-coil 自带 coil-compose 2.6.0，编译版本是
    // Kotlin 1.9.24 / Compose 1.6.7 / compileSdk 34，metadata 与 Kotlin 2.1 运行时兼容；
    // 再往上的 0.15+ 已经要求更新的 Compose / compileSdk 档，不跟着升。
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("me.saket.telephoto:zoomable-image-coil:0.13.0")

    // 测试相关的库同样使用稳定版或原有的 libs
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
