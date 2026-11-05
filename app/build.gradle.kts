import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mammyframe.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mammyframe.app"
        minSdk = 31
        targetSdk = 36
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0.$versionCode"
        // Tablets and phones are ARM; leaving out the emulator-only libraries keeps the app smaller.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        // The build server passes the same keystore every time, so new versions install as updates.
        val ks = providers.gradleProperty("signKeystore").orNull
        if (ks != null) {
            create("stable") {
                storeFile = file(ks)
                storePassword = "android"
                keyAlias = "mammyframe"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("stable") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // Compress the AI library inside the download (it's there twice: 64-bit and older 32-bit tablets).
        jniLibs { useLegacyPackaging = true }
    }

    androidResources {
        // The AI model is read straight from the app, so don't compress it.
        noCompress += "onnx"
    }

    lint {
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    // AI rotate: runs the orientation model on the tablet, and reads/writes the photos' rotation tags.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
}
