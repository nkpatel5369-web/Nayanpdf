import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "npdf.engine"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Each test in its own process: a native crash fails one test, not the run.
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
        consumerProguardFiles("consumer-rules.pro")
    }

    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs { useLegacyPackaging = false }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // 1.28.2, the MuPDF version the Windows build uses. 1.28.5 mis-spaces the text that
    // follows a removed run of characters (redaction and text editing): the rest of the
    // line slides left by the width of the first removed character.
    api("com.artifex.mupdf:fitz:1.28.2")

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.exifinterface:exifinterface:1.4.1")
    androidTestUtil("androidx.test:orchestrator:1.6.1")
    androidTestUtil("androidx.test.services:test-services:1.6.0")
}
