import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/** Copies misaki's lexicons from the extension into the app's assets (assets/lexicon/us.json, gb.json). */
abstract class LexiconAssets : DefaultTask() {
    @get:InputDirectory
    abstract val lexicon: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun copy() {
        val target = outputDirectory.get().dir("lexicon").asFile
        target.deleteRecursively()
        target.mkdirs()
        for (name in listOf("us.json", "gb.json")) lexicon.get().file(name).asFile.copyTo(target.resolve(name))
    }
}

val lexiconAssets = tasks.register<LexiconAssets>("lexiconAssets") {
    lexicon.set(rootProject.layout.projectDirectory.dir("extension/lexicon"))
}

android {
    namespace = "com.orexii.aimarkdownreader"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.orexii.aimarkdownreader"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        // Instrumented tests read the same parity goldens as the JVM tests.
        getByName("androidTest").assets.directories.add("../core/src/test/resources")
    }

    packaging {
        resources.excludes += listOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(lexiconAssets, LexiconAssets::outputDirectory)
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":espeak"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.media3.session)
    implementation(libs.media3.common)
    implementation(libs.webkit)
    implementation(libs.datastore.preferences)
    implementation(libs.documentfile)
    implementation(libs.onnxruntime.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.uiautomator)
}
