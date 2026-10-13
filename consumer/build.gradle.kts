plugins {
    id("com.android.application") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "2.0.20"
}
android {
    namespace = "io.vedika.sdk.ar.consumer"
    compileSdk = 35
    defaultConfig { applicationId = "io.vedika.sdk.ar.consumer"; minSdk = 24; targetSdk = 35 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = false } }
}
dependencies {
    // Deliberately ONLY the AR dependency: public types must be transitive.
    implementation("io.vedika:vedika-android-ar:1.1.0")
}
