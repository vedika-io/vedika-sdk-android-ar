// Vedika Android AR SDK — native ARCore room-capture module.
//
// Companion to `sdks/android` (the plain-JVM/OkHttp Vastu client): this
// module is a real `com.android.library` because ARCore's `Session` and
// `GLSurfaceView`-based rendering both need Android framework classes that
// cannot compile under a bare `kotlin("jvm")` module. The pure capture
// model and geometry (`RoomCaptureModel.kt`, `RoomCaptureGeometry.kt`,
// `RoomCaptureTypes.kt`) touch no Android or ARCore type on purpose, so
// they still run as ordinary JVM unit tests (`./gradlew testDebugUnitTest`)
// with no device or emulator — only the ARCore-facing views
// (`VastuArCoreCaptureView` and friends) need a real device.
plugins {
    id("com.android.library") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "2.0.20"
    `maven-publish`
}

group = "io.vedika"
version = "1.2.0"

android {
    namespace = "io.vedika.sdk.ar"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    publishing { singleVariant("release") }
    packaging { resources { excludes += "META-INF/*.kotlin_module" } }
    sourceSets["test"].resources.apply {
        srcDir("../fixtures")
        include("vastu-room-capture/**")
    }
}

dependencies {
    // The core Vastu client (VastuRoomCapture and friends, VedikaClient,
    // VastuContracts.arRoomCapture) — resolved against local source through
    // `settings.gradle.kts`'s `includeBuild("../android")`, the same
    // composite-build pattern `sdks/android-acceptance` uses, so this module
    // always builds against the current SDK source, never a stale jar.
    api("com.github.vedika-io:vedika-sdk-android:1.1.0")

    // ARCore: Session, Frame, Camera, Pose, hit-testing, Depth API support
    // checks. Google Maven metadata still lists 1.56.0 as latest (2026-10-13).
    // Its arm64-v8a/x86_64 PT_LOAD segments are already 16 KB aligned;
    // verify the complete consumer APK, including transitive native libraries.
    api("com.google.ar:core:1.56.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Tink Android: Apache-2.0, pinned; keysets encrypted by Android Keystore.
    api("com.google.crypto.tink:tink-android:1.15.0")
    // Resolve the legacy AR annotation artifact to the encrypted-store JVM
    // variant. Publish this dependency so consuming apps do not need exclusions.
    implementation("androidx.annotation:annotation:1.8.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // Loopback server for VastuArCoreUploaderTest; same version as sdks/android.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

// Maven publishing — `./gradlew publish` (configure the repository URL/creds in CI).
publishing {
    repositories {
        maven {
            name = "consumer"
            url = layout.buildDirectory.dir("consumer-maven").get().asFile.toURI()
        }
    }
    publications {
        register<MavenPublication>("release") {
            groupId = "io.vedika"
            artifactId = "vedika-android-ar"
            version = project.version.toString()
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("Vedika Android AR")
                description.set("Native ARCore room-capture module for the Vedika Android SDK (vedika.roomCapture/1).")
                url.set("https://vedika.io")
                licenses { license { name.set("Proprietary — XALEN Technology"); url.set("https://vedika.io/terms") } }
                developers { developer { id.set("xalen"); name.set("XALEN Technology Pvt Ltd") } }
                scm { url.set("https://github.com/vedika-io/vedika-sdk-android-ar") }
            }
        }
    }
}
