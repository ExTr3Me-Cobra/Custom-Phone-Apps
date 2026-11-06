import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.2.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.0"
    id("org.jetbrains.compose") version "1.9.3"
}

group = "mammyframe"
// Each GitHub build gets a higher version number (Windows installers need three numbers, first above 0).
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
version = "1.0.$buildNumber"

kotlin { jvmToolchain(21) }

// The AI model and the script font are shared with the Android app (no second copy in the repository).
sourceSets.main {
    resources.srcDir("../app/src/main/assets")
    resources.srcDir("../app/src/main/res/font")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.desktop.windows_x64)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    // Ai Rotate: the AI itself, reading camera rotation tags, writing them back losslessly, and its list.
    implementation("com.microsoft.onnxruntime:onnxruntime:1.31.1")
    implementation("com.drewnoakes:metadata-extractor:2.19.0")
    implementation("org.apache.commons:commons-imaging:1.0.0-alpha6")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    // WebP photos (reading only).
    implementation("com.twelvemonkeys.imageio:imageio-webp:3.12.0")
    // Keeping the PC awake during the slideshow.
    implementation("net.java.dev.jna:jna-platform:5.17.0")
}

compose.desktop {
    application {
        mainClass = "mammyframe.MainKt"
        jvmArgs += listOf("-Xmx2g")
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Mammy Frame"
            packageVersion = "1.0.$buildNumber"
            description = "A simple photo frame"
            vendor = "Mammy Frame"
            // Only the parts of Java the app uses (found with ./gradlew suggestRuntimeModules).
            modules("java.instrument", "java.sql", "jdk.unsupported", "java.naming", "jdk.accessibility")
            windows {
                menu = true
                shortcut = true
                perUserInstall = true
                dirChooser = false
                menuGroup = "Mammy Frame"
                // Same id every build, so a new version installs over the old one.
                upgradeUuid = "6c3f7a52-1d8e-4b7a-9a43-5d2f8e0b9c41"
                iconFile.set(project.file("icon.ico"))
            }
        }
    }
}

// Checks run on the build machine (not part of the app): the AI on sample photos, lossless JPEG
// turning, and pictures of the screens.
tasks.register<JavaExec>("selfTest") {
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("mammyframe.SelfTestKt")
    args = (project.findProperty("testArgs") as String? ?: "").split(" ").filter { it.isNotBlank() }
    jvmArgs("-Xmx3g")
}
