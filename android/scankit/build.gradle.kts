import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Document scanning and Office export in plain Kotlin (no Android types), so every
// algorithm is unit-tested on the JVM with synthetic photos as well as on devices.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // QR code decoding (Apache-2.0, pure Java, no network code).
    implementation("com.google.zxing:core:3.5.4")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    maxHeapSize = "2g"
    systemProperty("java.awt.headless", "true")
    System.getProperty("npdf.officeOut")?.let { systemProperty("npdf.officeOut", it) }
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL; showStandardStreams = true }
}
