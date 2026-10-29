import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.test)
}

/**
 * Tests the staging build (the release build, shrunk by R8, under its own ID) from outside, through its screen and
 * adb, as a user would. Run with ./gradlew :releasetest:connectedStagingAndroidTest, or install the two APKs and
 * use am instrument (see the README).
 */
android {
    namespace = "com.orexii.aimarkdownreader.releasetest"
    compileSdk = 37
    targetProjectPath = ":app"

    defaultConfig {
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        create("staging") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // The tests run in their own app, not inside the app under test.
    experimentalProperties["android.experimental.self-instrumenting"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    beforeVariants(selector().withBuildType("debug")) { it.enable = false }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.junit)
    implementation(libs.uiautomator)
}
