plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.retrotv.emu"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.retrotv.emu"
        manifestPlaceholders["appLabel"] = "@string/app_name"
        minSdk = 26          // Android 8.0+ (типичные TCL Android TV)
        targetSdk = 34
        versionCode = 6
        versionName = "1.5"
        ndk {
            // TCL бывают и 32-битные, и 64-битные — собираем оба ABI
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        create("preview") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
            manifestPlaceholders["appLabel"] = "RetroTV Preview"
            matchingFallbacks += listOf("debug")
        }
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
    // Pinned engine source with reviewed RetroTV audio and JNI patches.
    implementation(project(":emulation"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
}
