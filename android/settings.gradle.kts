pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // MuPDF for Android (Artifex): the same PDF engine as the Windows app.
        maven("https://maven.ghostscript.com") { content { includeGroup("com.artifex.mupdf") } }
        // Tesseract OCR for Android (Apache-2.0), published through JitPack.
        maven("https://jitpack.io") { content { includeGroup("com.github.adaptech-cz.Tesseract4Android") } }
    }
}

rootProject.name = "npdf"
include(":engine", ":app")
