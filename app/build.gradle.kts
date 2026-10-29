import java.util.Properties
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

/**
 * The upload key for Google Play: from keystore.properties (not committed) or, in CI, from the environment
 * (AMR_KEYSTORE_FILE, AMR_KEYSTORE_PASSWORD, AMR_KEY_ALIAS, AMR_KEY_PASSWORD). Unsigned when neither is there.
 */
val signing: Map<String, String> = run {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) {
        val properties = Properties().apply { file.inputStream().use(::load) }
        properties.stringPropertyNames().associateWith { properties.getProperty(it) }
    } else {
        listOf("AMR_KEYSTORE_FILE", "AMR_KEYSTORE_PASSWORD", "AMR_KEY_ALIAS", "AMR_KEY_PASSWORD")
            .mapNotNull { name -> System.getenv(name)?.takeIf { it.isNotEmpty() }?.let { name to it } }.toMap()
    }
}

android {
    namespace = "com.orexii.aimarkdownreader"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.orexii.aimarkdownreader"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("amr.versionCode").get().toInt()
        versionName = providers.gradleProperty("amr.versionName").get()
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        if (signing.size == 4) {
            create("upload") {
                storeFile = rootProject.file(signing.getValue("AMR_KEYSTORE_FILE"))
                storePassword = signing.getValue("AMR_KEYSTORE_PASSWORD")
                keyAlias = signing.getValue("AMR_KEY_ALIAS")
                keyPassword = signing.getValue("AMR_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        // Test builds install next to the Play version, so they never replace it (or its downloaded voice).
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            if (signing.size == 4) signingConfig = signingConfigs.getByName("upload")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // The release build (shrunk by R8), signed with the debug key under its own ID, to try on a phone.
        create("staging") {
            initWith(getByName("release"))
            applicationIdSuffix = ".staging"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
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
