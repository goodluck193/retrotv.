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
        minSdk = 26          // Android 8.0+
        targetSdk = 34
        versionCode = 9
        versionName = "1.6.1"
        ndk {
            // Both 32-bit and 64-bit TV chipsets.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("previewTest") {
            // Public test key: reproducible preview updates only, never a production identity.
            storeFile = rootProject.file("config/preview-test.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        create("preview") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".preview.console"
            versionNameSuffix = "-preview"
            signingConfig = signingConfigs.getByName("previewTest")
            manifestPlaceholders["appLabel"] = "Retro Console Preview"
            matchingFallbacks += listOf("debug")
        }
        release {
            isMinifyEnabled = false
        }
    }

    packaging {
        // JVM debug-agent replacement bytecode is unused by Android TV.
        resources.excludes += "DebugProbesKt.bin"
        jniLibs {
            // Cores must be extracted for dlopen on all supported API levels.
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
    // Pinned engine source with reviewed Retro Console audio and JNI patches.
    implementation(project(":emulation"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
}
