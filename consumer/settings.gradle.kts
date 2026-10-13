pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral(); maven { url = uri("https://jitpack.io") } }
}
rootProject.name = "consumer"
includeBuild("..") {
    dependencySubstitution {
        substitute(module("io.vedika:vedika-android-ar")).using(project(":"))
    }
}
