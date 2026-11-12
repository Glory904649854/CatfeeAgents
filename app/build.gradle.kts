import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * DECISION 模式的默认接入信息从 local.properties 读取。
 * Key 不进入仓库；其他开发者未配置时字段为空，可在 App 内手动填写。
 */
val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) {
    localPropsFile.inputStream().use { localProps.load(it) }
}

fun localValue(key: String, fallback: String = ""): String =
    localProps.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() } ?: fallback

fun gradleEscaped(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.catfee.rootauto"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.catfee.rootauto"
        minSdk = 26
        targetSdk = 34
        versionCode = 10000
        versionName = "1.0.0"

        buildConfigField(
            "String",
            "DECISION_BASE_URL",
            "\"${gradleEscaped(localValue("decision.baseUrl", "https://llm-cev4lnmqq9bzm5of.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"))}\""
        )
        buildConfigField(
            "String",
            "DECISION_API_KEY",
            "\"${gradleEscaped(localValue("decision.apiKey"))}\""
        )
        buildConfigField(
            "String",
            "DECISION_MODEL",
            "\"${gradleEscaped(localValue("decision.model", "decision-model-preview"))}\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
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

    // daemon 二进制放在 build 期复制到 assets, 避免多 ABI 同名文件在 assets 根冲突
    sourceSets["main"].assets.srcDir("${rootDir}/app/src/main/assets")
}

/**
 * 功能: 把 NDK 编译产物按 ABI 目录复制到 assets/daemon/ 下
 * 使用场景: App 内一键安装 Magisk 模块时按设备 ABI 取对应二进制
 */
val copyDaemonAssets by tasks.registering(Copy::class) {
    from("${rootDir}/daemon/dist") {
        into("daemon")
    }
    into("${projectDir}/src/main/assets")
}

tasks.named("preBuild") { dependsOn(copyDaemonAssets) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // libsu: Magisk 官方作者提供的 root shell 库, 避免 Runtime.exec("su") 死锁
    implementation("com.github.topjohnwu.libsu:core:5.0.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 离线中文 OCR: ML Kit 兜底
    implementation("com.google.mlkit:text-recognition-chinese:16.0.0")
    // 本地 PP-OCR (det + rec ONNX): 对浅色小题号/小字比 ML Kit 稳
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
