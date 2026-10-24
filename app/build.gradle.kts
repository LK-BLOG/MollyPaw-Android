plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.mollypaw.remote"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mollypaw.remote"
        minSdk = 26
        targetSdk = 35
        // 版本号与主线保持一致。发布时由 tools/apply_version.py 按 tag 覆盖，
        // 这里的值只是本地开发的默认值。
        // 两边同版本号，排查问题时能一眼对上是谁的哪个版本。
        versionCode = 200
        versionName = "0.0.0.2.0"
        vectorDrawables { useSupportLibrary = true }
    }

    // 发布签名：仓库里自带一个 beta 自签证书（keystore/mollypaw-release.jks）。
    // 这是开源 sideload 的常规做法 —— 不走 Google Play，签名只用来让系统认得出
    // 「同一个 App 的升级」。密钥和口令都在仓库里，明摆着不当事密。
    // 不加这段的话 assembleRelease 只会产出 app-release-unsigned.apk，
    // 用户下载后点安装会直接「应用未安装」。
    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/mollypaw-release.jks")
            storePassword = "mollypaw123"
            keyAlias = "mollypaw"
            keyPassword = "mollypaw123"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // 默认走官方中继；自建地址可以在设置里改
            buildConfigField("String", "DEFAULT_RELAY_URL",
                "\"wss://mollypaw-online.pages.dev/server\"")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            buildConfigField("String", "DEFAULT_RELAY_URL",
                "\"wss://mollypaw-online.pages.dev/server\"")
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // WebSocket：OkHttp 自带，不需要额外的 ws 库
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    // 协程：Compose + Flow 都要它（小A记 那套里也有，缓存里现成）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // foundation / material3 / material-icons：Compose UI 必需
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // X25519：Android JCA 没有原生实现，用 BouncyCastle。
    // AES-GCM / HKDF 用 JCA 自带的（HKDF 手写，见 crypto/Hkdf.kt）。
    //
    // 版本 1.78.1 —— jar 是从 Gradle 自带的库里拷到本地 m2 仓库的
    // （见 tools/setup_local_deps.py），不用联网下载。
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    testImplementation("junit:junit:4.13.2")
    // junit 4 自己不带 hamcrest，必须显式声明，否则跑测试会报
    // NoClassDefFoundError: org/hamcrest/SelfDescribing
    testImplementation("org.hamcrest:hamcrest-core:1.3")
    // 不引 Compose UI 测试与 navigation —— 本项目用不到，
    // 缓存里也没有，离线编译能省一批下载。
}
