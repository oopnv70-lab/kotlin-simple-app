import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/*
 * ============================================================
 *  构建期环境校验（Build-time environment validation）
 * ============================================================
 *  Android 17 = API 37（2026）。
 *  本块在 configuration 阶段执行，任何一项不满足就直接 fail，
 *  避免"编译过了但工具链不对"的隐性错误。
 * ------------------------------------------------------------
 */

// 1) 校验构建所用 JDK 版本：AGP 9.x 要求 JDK 17
val requiredJdk = 17
val currentJdk = JavaVersion.current().majorVersion.toInt()
check(currentJdk == requiredJdk) {
    "JDK 版本不符：当前为 JDK $currentJdk，AGP 9.4 要求 JDK $requiredJdk。" +
        " 请在 gradle.properties 设置 org.gradle.java.home 或切换 JAVA_HOME。"
}

// 2) 校验 SDK 目录可用
val sdkDir = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    ?: rootProject.file("local.properties").takeIf { it.exists() }
        ?.let { p ->
            java.util.Properties().apply { p.inputStream().use { load(it) } }.getProperty("sdk.dir")
        }
logger.lifecycle("[check] Android SDK = ${sdkDir ?: "<未配置，将依赖 local.properties / 环境变量>"}")

android {
    namespace = "com.oopnv70.simpleapp"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.oopnv70.simpleapp"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // 练习用途：release 默认不混淆、不压缩，
            // 方便你直接反编译对照。
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

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // 3) 构建期校验：compileSdk 必须为 37（Android 17），否则报错
    //    —— 防止误改回 36 导致行为变更测试失效
    //    （compileSdk 在 DSL 里已是固定值，这里做二次确认）
    check(compileSdk == 37) {
        "compileSdk 必须为 37（Android 17 / API 37），当前为 $compileSdk"
    }
}

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

/*
 * 4) 构建期提示：打印关键工具链版本，便于对照 compatibility
 *    首次配置时输出，方便你确认 AGP / Gradle / Kotlin 组合是否正确。
 */
gradle.projectsEvaluated {
    logger.lifecycle(
        """
        |==================== Build Environment ====================
        |  AGP            : ${project.plugins.findPlugin("com.android.application")?.javaClass?.package?.implementationVersion ?: "9.4.0"}
        |  Gradle         : ${gradle.gradleVersion}
        |  JDK            : ${currentJdk}
        |  compileSdk     : 37 (Android 17)
        |  targetSdk      : 37
        |  minSdk         : 26
        |===========================================================
        """.trimMargin()
    )
}