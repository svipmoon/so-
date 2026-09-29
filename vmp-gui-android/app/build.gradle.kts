plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vmpacker.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vmpacker.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 使用 debug 签名方便 CI 直接出可安装包；正式发布可替换为自己的签名
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // 将 vmpacker 二进制以 native lib 方式打入 APK（lib/arm64-v8a/）
    // Android 系统会自动以「只读+可执行」方式提取该目录，符合 W^X 安全策略
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/engineLibs")
        }
    }

    packaging {
        jniLibs {
            // 以未压缩方式存储，安装时提取到 nativeLibraryDir（可执行）
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
}
