import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    // The app supplies the Android build of ONNX Runtime (same Java API); tests use the desktop one.
    compileOnly(libs.onnxruntime.jvm)
    testImplementation(libs.onnxruntime.jvm)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Parity tests read the extension's lexicons and the eSpeak data, and load a host build of the
// eSpeak JNI library when it exists (./gradlew :espeak:hostLibrary).
tasks.test {
    systemProperty("amr.root", rootDir.absolutePath)
    systemProperty("amr.espeakHostLib", rootProject.file("espeak/build/host/libespeakjni.so").absolutePath)
    // Real-model tests run when the pinned model is present (node tools/fetch-model.mjs .model compact).
    systemProperty("amr.modelDir", rootProject.file(".model").absolutePath)
    maxHeapSize = "2g"
}
