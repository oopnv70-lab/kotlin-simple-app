plugins {
    alias(libs.plugins.android.application)
    // AGP 9.0+ 内置 Kotlin，无需 org.jetbrains.kotlin.android
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.oopnv70.simpleapp"
    // Android 17 (API 37) 引入了 minor 版本，SDK 平台真实包名/目录为 android-37.0
    // 必须同时指定 compileSdkMinor，否则 AGP 会去找不存在的 android-37 目录
    compileSdk = 37
    compileSdkMinor = 0

    // NDK 版本必须与 CI 中安装的版本一致（AGP 9.4 期望 28.2.13676358）
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.oopnv70.simpleapp"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        // 只编译 arm64-v8a：覆盖绝大多数现代手机，构建更快、失败点更少
        ndk {
            abiFilters += "arm64-v8a"
        }

        // 挂上 CMake（Native 卡密校验核心）
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DCMAKE_BUILD_TYPE=Release"
                )
                cppFlags += "-O2"
            }
        }
    }

    // 指定 CMakeLists.txt 位置
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// 说明（AGP 9 内置 Kotlin）：
// 官方文档明确：使用内置 Kotlin 时无需设置 kotlin.compilerOptions.jvmTarget，
// 其值默认取 android.compileOptions.targetCompatibility（此处为 17）。

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    debugImplementation(libs.androidx.ui.tooling)
}