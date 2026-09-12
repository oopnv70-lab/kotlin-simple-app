import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/*
 * ============================================================
 *  构建期环境校验 + 自适应 SDK（Build-time env validation）
 * ============================================================
 *  目标：Android 17 = API 37（2026）。
 *  但 Android 17 的 SDK platform 在 Google 仓库里可能尚未以
 *  "platforms;android-37" 的稳定包名提供。
 *  因此这里做「自适应」：
 *    - 若本机已安装 android-37 -> 用 37（首选）
 *    - 否则自动选用本机已安装的最高版本（如 36）并给出警告
 *  这样既能表达"以 37 为目标"的意图，又不会因 SDK 缺失而构建失败。
 * ------------------------------------------------------------
 */

// 1) JDK 校验：AGP 9.x 需要 JDK 17
val requiredJdk = 17
val currentJdk = JavaVersion.current().majorVersion.toInt()
check(currentJdk >= requiredJdk) {
    "JDK 版本不符：当前 JDK $currentJdk，AGP 9.4 要求至少 JDK $requiredJdk。"
}

// 2) 解析 SDK 目录
val sdkDir: File? = run {
    val env = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    if (!env.isNullOrBlank()) File(env)
    else {
        val lp = rootProject.file("local.properties")
        if (lp.exists()) {
            java.util.Properties().apply { lp.inputStream().use { load(it) } }
                .getProperty("sdk.dir")?.let { File(it) }
        } else null
    }
}

// 3) 探测本机已安装的平台版本
val platformsDir = sdkDir?.resolve("platforms")
val installedApis: List<Int> = platformsDir?.listFiles()
    ?.mapNotNull { it.name.removePrefix("android-").toIntOrNull() }
    ?.sortedDescending()
    ?: emptyList()

logger.lifecycle("[check] Android SDK   = ${sdkDir ?: "<未配置>"}")
logger.lifecycle("[check] installed APIs = $installedApis")

// 4) 选择 compileSdk：优先 37，否则取已安装的最高版本
val preferredSdk = 37
val chosenSdk: Int = when {
    installedApis.contains(preferredSdk) -> preferredSdk
    installedApis.isNotEmpty() -> {
        val fb = installedApis.first()
        logger.warn(
            "[check] 未找到 android-$preferredSdk，自动降级为 android-$fb。" +
                " 若要严格使用 Android 17，请安装 platforms;android-$preferredSdk 后重建。"
        )
        fb
    }
    else -> {
        logger.warn("[check] 未检测到任何已安装平台，仍以 $preferredSdk 声明（由 AGP 决定后续行为）")
        preferredSdk
    }
}
logger.lifecycle("[check] compileSdk -> $chosenSdk")

android {
    namespace = "com.oopnv70.simpleapp"
    compileSdk = chosenSdk

    defaultConfig {
        applicationId = "com.oopnv70.simpleapp"
        minSdk = 26
        targetSdk = 37          // 目标仍是 Android 17
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
            // 练习用途：release 默认不混淆，方便反编译对照
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

/**
 * 5) 构建期打印工具链信息
 */
gradle.projectsEvaluated {
    logger.lifecycle(
        """
        |==================== Build Environment ====================
        |  Gradle         : ${gradle.gradleVersion}
        |  JDK            : $currentJdk
        |  compileSdk     : $chosenSdk
        |  targetSdk      : 37 (Android 17)
        |  minSdk         : 26
        |===========================================================
        """.trimMargin()
    )
}