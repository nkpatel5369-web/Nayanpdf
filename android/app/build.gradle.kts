import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI signs each test build with a key generated for that build only (see
// .github/workflows/android.yml). Local builds use the debug key.
val ciKeystore: String? = System.getenv("NPDF_KEYSTORE")
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

// OCR language data (tesseract-ocr/tessdata_fast tag 4.1.0, Apache-2.0), fetched
// at build time and checked against these hashes. The app never downloads anything.
val ocrData = mapOf(
    "eng" to "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2",
    "hin" to "4c73ffc59d497c186b19d1e90f5d721d678ea6b2e277b719bee4e2af12271825",
    "guj" to "fa69658614b4946a9afae8853d67e0689838803dfa3d12c2e35ec53ee6f8df34",
)
val ocrAssets = layout.buildDirectory.dir("generated/ocr-assets")

fun sha256(f: File): String = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

val fetchOcrData by tasks.registering {
    description = "Downloads the OCR language data and verifies its SHA-256."
    outputs.dir(ocrAssets)
    inputs.property("hashes", ocrData)
    doLast {
        val dir = ocrAssets.get().dir("tessdata").asFile.apply { mkdirs() }
        for ((lang, hash) in ocrData) {
            val f = File(dir, "$lang.traineddata")
            if (f.exists() && sha256(f) == hash) continue
            val url = URI("https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/4.1.0/$lang.traineddata").toURL()
            url.openStream().use { input -> f.outputStream().use { input.copyTo(it) } }
            val got = sha256(f)
            if (got != hash) {
                f.delete()
                throw GradleException("$lang.traineddata: SHA-256 $got does not match the pinned $hash")
            }
        }
    }
}

android {
    namespace = "npdf.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "in.nayanpdf.npdf"
        minSdk = 26
        targetSdk = 36
        versionCode = buildNumber
        versionName = "1.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
        // Phones and tablets (64- and 32-bit ARM) and 64-bit Intel (Chromebooks, emulators).
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
    }

    signingConfigs {
        create("ci") {
            if (ciKeystore != null) {
                storeFile = file(ciKeystore)
                storeType = "PKCS12"
                storePassword = System.getenv("NPDF_KEYSTORE_PASSWORD")
                keyAlias = "npdf"
                keyPassword = System.getenv("NPDF_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName(if (ciKeystore != null) "ci" else "debug")
        }
    }
    // The instrumented tests run against the release build: exactly what is shipped.
    testBuildType = "release"

    sourceSets["main"].assets.srcDir(ocrAssets)
    // The app's tests use the engine's synthetic test documents.
    sourceSets["androidTest"].assets.srcDir("../engine/src/androidTest/assets")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    androidResources {
        noCompress += "traineddata"
    }

    packaging {
        // Native code stored compressed: a smaller download (it is unpacked once at install).
        jniLibs { useLegacyPackaging = true }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

tasks.named("preBuild") { dependsOn(fetchOcrData) }

dependencies {
    implementation(project(":engine"))
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.github.adaptech-cz.Tesseract4Android:tesseract4android:4.8.0")

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("junit:junit:4.13.2")
    androidTestUtil("androidx.test:orchestrator:1.6.1")
    androidTestUtil("androidx.test.services:test-services:1.6.0")
}
