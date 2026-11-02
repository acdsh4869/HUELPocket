import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 插件不写版本：版本由所在工程根目录统一提供。
// - 独立构建：根 build.gradle.kts 通过 libs.versions.toml 提供（AGP 8.7.3 / Kotlin 2.0.21）
// - 拷入主工程：主工程根目录已 apply false 了 com.android.application / kotlin.android / kotlin.compose，
//   com.android.library 与 com.android.application 同属 AGP 一个构件，无需额外声明。
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

group = property("glassui.group") as String
version = property("glassui.version") as String

android {
    namespace = "com.huelpocket.glassui"
    compileSdk = 36

    defaultConfig {
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            // 库自身不混淆，由主 App 的 R8 统一处理
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = false
        resValues = false
    }
}

kotlin {
    // 显式 API 模式：每个声明都必须写明 public/internal，防止内部实现意外泄漏成公开 API
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:${property("glassui.composeBom")}")
    api(composeBom)
    // 公开 API 中出现了 Modifier / Color / Shape / Dp / TextStyle / ImageVector 等类型，必须 api 暴露
    api("androidx.compose.ui:ui")
    api("androidx.compose.foundation:foundation")
    api("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:${property("glassui.activityCompose")}")
    // haze 只在内部使用，公开 API 不出现任何 haze 类型 → implementation
    implementation("dev.chrisbanes.haze:haze:${property("glassui.haze")}")
}
