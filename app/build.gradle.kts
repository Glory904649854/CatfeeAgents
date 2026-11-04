plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.catfee.rootauto"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.catfee.rootauto"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
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
