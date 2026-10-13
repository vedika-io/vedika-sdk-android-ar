pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories {
        maven { url = uri("../build/consumer-maven") }
        google(); mavenCentral(); maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "consumer"
