// Published mirror of sdks/android-ar (monorepo: vedika-io/vedika). Single
// Android-library module, no composite build — the monorepo copy resolves
// the core Vastu client through `includeBuild("../android")`; this published
// copy resolves it from JitPack instead (see build.gradle.kts).
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "vedika-android-ar"
