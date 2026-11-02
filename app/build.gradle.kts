import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// ── 签名 ───────────────────────────────────────────────────────────────
// 口袋财大自己的 release keystore（与上游 ZZUPocket 签名脱钩）。
// 凭据在仓库根的 keystore.properties（已 gitignore），keystore 文件本身在仓库之外，
// 由用户本人保管；缺失时 release 构建**直接失败**而不是静默退回 debug 签名。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val releaseStoreFile = keystoreProps.getProperty("storeFile")?.let { file(it) }
val hasReleaseSigning = releaseStoreFile?.exists() == true

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.huelpocket.app"

    // Lint baseline：只吸收本次任务之前就存在的历史问题（focus/schedule 等模块），
    // 新增代码的 lint 问题仍会照常报错，不被 baseline 掩盖。
    lint {
        baseline = file("lint-baseline.xml")
        abortOnError = true
    }

    compileSdk = 36

    defaultConfig {
        applicationId = "com.huelpocket.app"
        minSdk = 31
        targetSdk = 36
        versionCode = 89
        // 版本号从 0.1.0 重新起算（fork 残留的 0.33.x 与上游脱钩）；
        // ⚠️ 升级判断只认 versionCode（Android 的规则），versionName 是给人看的；
        // 每次发版 versionCode 必须 +1。versionCode 归零后旧包（97+）无法覆盖安装，需卸载重装。
        versionName = "0.15.20"

        // 瘦身：本应用无 JNI 业务代码，仅保留扫码库所需 ABI；中文资源全量保留。
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        resourceConfigurations += listOf("zh")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("huelpocket") {
                storeFile = releaseStoreFile
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // debug 包用 debug 签名（内测真机分发走 debug 包，不走 release 签名）
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("huelpocket")
            } else {
                // 发版卫生：release 必须用自己的签名，缺失直接失败，禁止静默退回 debug
                throw GradleException(
                    "release 构建缺少签名：请在仓库根创建 keystore.properties " +
                        "（storeFile/storePassword/keyAlias/keyPassword），keystore 由用户本人保管。",
                )
            }
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

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Compose 编译器报告：输出 unstable 类与不可跳过 composable 清单到 build/compose_compiler/。
// 只影响构建产物，不进 APK，不改变运行时行为。
composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
    metricsDestination = layout.buildDirectory.dir("compose_compiler")
}

// Room 导出 schema，便于日后写迁移。ksp{} 是项目级扩展，必须放在 android{} 之外。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":glass-ui"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    // 定时采样电量：学校不提供历史用电量，只能靠自己按点采样差分（每 4 小时一次）
    implementation(libs.androidx.work.runtime.ktx)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Material You 配色生成（由种子色算出整套 M3 浅/深配色，见 ui/theme/MaterialYouScheme.kt）
    implementation(libs.material.color.utilities)

    // 网络（不用 Retrofit：接口少，且 CAS 登录的 POST 走 query string，裸 OkHttp 更直接）
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // 本地存储
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    // 图书馆扫码：CameraX 取流 + ML Kit 解析二维码（不新增网络接口）
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
