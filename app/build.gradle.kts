plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.labelrescue.app"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "com.labelrescue.app"
        minSdk = 26
        targetSdk = 37
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1.$versionCode"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Release signing comes from CI (GitHub Secrets) through environment variables.
        val ks = System.getenv("LABELRESCUE_KEYSTORE_FILE")
        if (!ks.isNullOrBlank() && file(ks).exists()) {
            create("release") {
                storeFile = file(ks)
                storePassword = System.getenv("LABELRESCUE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("LABELRESCUE_KEY_ALIAS")
                keyPassword = System.getenv("LABELRESCUE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    androidResources {
        // Memory-map the models straight from the APK.
        noCompress += "tflite"
    }

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/modelAssets").get().asFile)
}

// The trained models are packaged as assets: only those the accuracy benchmark kept
// (models/manifest.json, "enabled": true), plus the manifest itself.
val modelsDir = rootProject.layout.projectDirectory.dir("models")
val copyModels = tasks.register<Sync>("copyModelAssets") {
    val manifest = modelsDir.file("manifest.json").asFile
    inputs.file(manifest).optional()
    val enabled: List<String> = if (manifest.exists()) {
        @Suppress("UNCHECKED_CAST")
        val m = groovy.json.JsonSlurper().parse(manifest) as Map<String, Any?>
        ((m["models"] as? Map<String, Any?>) ?: emptyMap()).filter { (it.value as? Map<*, *>)?.get("enabled") == true }.keys.toList()
    } else emptyList()
    from(modelsDir) { include(enabled.map { "$it.tflite" } + "manifest.json") }
    into(layout.buildDirectory.dir("generated/modelAssets/models"))
}
tasks.named("preBuild") { dependsOn(copyModels) }

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.mlkit.text)
    implementation(libs.mlkit.barcode)
    implementation(libs.zxing.core)
    implementation(libs.security.crypto)
    implementation(libs.exifinterface)
    implementation(libs.coroutines.android)
    implementation(libs.litert)
    implementation(libs.litert.gpu)
    implementation(libs.litert.gpu.api)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
