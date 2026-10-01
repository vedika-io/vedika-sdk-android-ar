// Vedika Android AR SDK — native ARCore room-capture module.
//
// Published mirror of `sdks/android-ar` in the vedika-io/vedika monorepo
// (single source of truth; sync the same way vedika-io/vedika-sdk-android
// already mirrors sdks/android). This is a real `com.android.library`
// because ARCore's `Session` and `GLSurfaceView`-based rendering both need
// Android framework classes that cannot compile under a bare `kotlin("jvm")`
// module. The pure capture model and geometry (`RoomCaptureModel.kt`,
// `RoomCaptureGeometry.kt`, `RoomCaptureQuality.kt`, `RoomCaptureTypes.kt`)
// touch no Android or ARCore type on purpose, so they still run as ordinary
// JVM unit tests (`./gradlew testDebugUnitTest`) with no device or emulator
// — only the ARCore-facing views (`VastuArCoreCaptureView` and friends) need
// a real device.
plugins {
    id("com.android.library") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "2.0.20"
    `maven-publish`
}

// JitPack builds this module by invoking Gradle with `-Pgroup=com.github.vedika-io
// -Pversion=<tag or commit>`, the same mechanism vedika-io/vedika-sdk-android
// uses, so it publishes under the coordinate a consumer actually asks for
// (`com.github.vedika-io:vedika-android-ar:<tag>`). A plain `group = "..."` /
// `version = "..."` assignment runs AFTER Gradle applies those `-P` values and
// would silently overwrite them back to the hardcoded default. Fall back to
// the local defaults only when the property is absent (a bare `./gradlew jar`
// on a dev machine) or still Gradle's own "unspecified" placeholder.
group = (findProperty("group") as String?).takeUnless { it.isNullOrBlank() } ?: "io.vedika"
version = (findProperty("version") as String?).takeUnless { it.isNullOrBlank() || it == "unspecified" } ?: "1.0.1"

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
}

dependencies {
    // The core Vastu client (VastuRoomCapture and friends, VedikaClient,
    // VastuContracts.arRoomCapture) — resolved from JitPack here (the
    // monorepo copy resolves it from local source via includeBuild instead;
    // see that copy's settings.gradle.kts). Bump this alongside the source
    // module's own published version.
    implementation("com.github.vedika-io:vedika-sdk-android:1.0.4")

    // ARCore: Session, Frame, Camera, Pose, hit-testing, Depth API support
    // checks. Current stable per Maven Central (checked 2026-09-23).
    implementation("com.google.ar:core:1.56.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // Loopback server for VastuArCoreUploaderTest.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

publishing {
    publications {
        register<MavenPublication>("release") {
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
