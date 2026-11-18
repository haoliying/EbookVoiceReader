plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ebook.voicereader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ebook.voicereader"
        minSdk = 24
        targetSdk = 34
        versionCode = 6
        versionName = "3.2"

        ndk {
            // sherpa-onnx 内置预编译 so，覆盖主流真机与 x86_64 模拟器
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        viewBinding = true
    }
    androidResources {
        // 模型与词典需以未压缩方式打包，供原生层直接读取
        noCompress += listOf("onnx", "fst", "txt")
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // PDF text extraction
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // HTML/XML parsing (for EPUB & DOCX)
    implementation("org.jsoup:jsoup:1.17.2")

    // OCR - Google ML Kit (offline after model download)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // 内置离线语音合成引擎（sherpa-onnx，Apache-2.0，含 VITS 中文模型）
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
}
