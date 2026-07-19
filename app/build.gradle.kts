plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.retrotv.emu"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.retrotv.emu"
        minSdk = 26          // Android 8.0+ (типичные TCL Android TV)
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk {
            // TCL бывают и 32-битные, и 64-битные — собираем оба ABI
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    packaging {
        jniLibs {
            // Ядра должны быть распакованы на диск, чтобы dlopen работал на любом targetSdk
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Эмуляционное ядро-обвязка (libretro для Android). При проблемах со сборкой
    // проверьте актуальный тег на https://github.com/Swordfish90/LibretroDroid/releases
    implementation("com.github.Swordfish90:LibretroDroid:0.12.0")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
