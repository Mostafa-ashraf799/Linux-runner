plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.binarybeast.linuxrunner"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.binarybeast.linuxrunner"
        minSdk = 26
        targetSdk = 34
        // versionCode MUST increase on every build you intend to install
        // over a previous one — Android silently keeps the old APK
        // running if the versionCode matches, even after a fresh
        // "install", which is exactly what caused the fixed download
        // code to appear not to take effect during testing.
        versionCode = 5
        versionName = "0.5-mvp"

        // We only ship/support arm64 devices for now (nearly all modern phones).
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        // A stable debug keystore committed to the repo (app/keystore/).
        // Without this, Android Gradle Plugin generates a fresh, random
        // debug keystore on whichever machine runs the build — and since
        // GitHub Actions spins up a brand-new machine on every run, every
        // build ended up signed with a DIFFERENT key. Android refuses to
        // install an update over an existing app if the signature doesn't
        // match ("توقيع غير متطابق"), which is exactly the install failure
        // seen during testing — it wasn't a code bug, it was this.
        getByName("debug") {
            storeFile = file("keystore/binarybeast-debug.keystore")
            storePassword = "binarybeast123"
            keyAlias = "binarybeast-debug"
            keyPassword = "binarybeast123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0") // ActivityResultContracts (file picker)
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // XZ decompression for .tar.xz rootfs tarballs (Debian, Kali).
    // Pure Java, no native code needed — safe to include unconditionally.
    implementation("org.tukaani:xz:1.9")
}
