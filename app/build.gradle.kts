import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.peektts"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.peektts"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("release") {
            val keystoreFile = rootProject.file("keystore/peektts.keystore.p12")
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = "peektts123"
                keyAlias = "peektts"
                keyPassword = "peektts123"
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // Use the same signing config for debug so we can test with native libs
            signingConfig = signingConfigs.getByName("release")
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
        compose = false
        viewBinding = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // For downloading models at runtime
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // For extracting tar.bz2 model archives (Android toybox tar 不支持 --strip-components，
    // 会导致 Kokoro TTS 解压后 voices.bin/tokens.txt/espeak-ng-data 全在嵌套子目录里，
    // native init 找不到文件直接 abort 整个进程)
    implementation("org.apache.commons:commons-compress:1.26.1")
}
