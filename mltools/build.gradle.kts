import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// JVM-only tools for the on-device models: synthetic training data (rendered with Java2D, so it
// can't live in :core, which ships in the app) and the with/without-model accuracy benchmark.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.zxing.core)
    implementation(libs.onnxruntime.jvm)
    testImplementation(libs.junit)
}

val modelsDir = rootProject.layout.projectDirectory.dir("models")

/** ./gradlew :mltools:datasets -Pout=build/datasets -Pscale=1.0 */
tasks.register<JavaExec>("datasets") {
    group = "ml"
    description = "Generates synthetic training data for the three models."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.labelrescue.mltools.DatasetsKt")
    jvmArgs("-Xmx3g", "-Djava.awt.headless=true")
    args(
        (project.findProperty("out") as String?) ?: layout.buildDirectory.dir("datasets").get().asFile.path,
        (project.findProperty("scale") as String?) ?: "1.0",
        (project.findProperty("which") as String?) ?: "all",
    )
}

/** ./gradlew :mltools:benchmark  — compares recovery with/without each model and writes models/manifest.json. */
tasks.register<JavaExec>("benchmark") {
    group = "ml"
    description = "Recovery rate with and without each model; keeps only models that help."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.labelrescue.mltools.BenchmarkKt")
    jvmArgs("-Xmx3g", "-Djava.awt.headless=true")
    args(modelsDir.asFile.path, (project.findProperty("cases") as String?) ?: "96")
}

tasks.test {
    jvmArgs("-Djava.awt.headless=true")
    maxHeapSize = "2g"
    testLogging { showStandardStreams = true; events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

/**
 * ./gradlew :mltools:curateCrops -Pcrops=<export>/training_crops
 * Re-cuts and verifies the digit crops of an app export into training/real_crops (see CurateCrops.kt).
 */
tasks.register<JavaExec>("curateCrops") {
    group = "ml"
    description = "Re-cuts and verifies exported digit crops into training/real_crops."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.labelrescue.mltools.CurateCropsKt")
    jvmArgs("-Xmx2g", "-Djava.awt.headless=true")
    args(
        (project.findProperty("crops") as String?) ?: "",
        rootProject.layout.projectDirectory.dir("training/real_crops").asFile.path,
        modelsDir.asFile.path,
    )
}
