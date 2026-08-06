plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.native.cocoapods")
    alias(libs.plugins.kotlin.serialization) version libs.versions.kotlin
}

group = "com.k2k"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    google()
    gradlePluginPortal()
    mavenCentral()
}

kotlin {
    applyDefaultHierarchyTemplate()
    // NOTE: androidTarget() is deliberately absent. moviePicker's clone declares it via
    // com.android.library, but AGP 9 rejects com.android.library alongside the Kotlin
    // Multiplatform plugin outright, and passmanShared/passmanClient are on AGP 9.3.1. The
    // replacement (com.android.kotlin.multiplatform.library) cannot bridge either: under
    // AGP 8.13 its DSL block needs `import com.android.build.api.dsl.androidLibrary`, and
    // under AGP 9 that import does not exist and the block is renamed `android`.
    //
    // Restoring the Android target requires moviePicker to move to AGP 9 first. Until then
    // the android source sets below are carried but not compiled; Android consumers resolve
    // the jvm variant, which is what passmanShared already does today.
    jvm()

    // iosX64 is required by :presenter. It picks its iOS target from the SDK_NAME and
    // NATIVE_ARCH environment variables, which only Xcode sets, so from a terminal it falls
    // back to iosX64 and cannot resolve this project without it.
    //
    // framework("k2k") keeps the named framework the CocoaPods integration expects;
    // isStatic comes from the moviePicker side.
    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        iosTarget.binaries.framework("k2k") {
            isStatic = true
        }
    }

    sourceSets {
        getByName("commonMain") {
            dependencies {
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.network)
                implementation(libs.ktor.network.tls)
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization)

                implementation(libs.ktor.client.cio)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)

                implementation(libs.ktor.server.content.negotiation)
                implementation(libs.ktor.server.core)

            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        // jvmSources is shared by the jvm and android targets: both run the Netty/CIO
        // server, native cannot. Named source sets use getByName/create rather than the
        // by getting / by creating delegates, which Gradle 9.6 deprecates.
        val jvmSources = create("jvmSources") {
            dependencies {
                api(libs.ktor.server.netty)
                api(libs.ktor.server.cio)
            }
        }
        getByName("jvmMain") {
            dependsOn(jvmSources)
            dependencies {
                implementation(libs.ktor.client.okhttp)
            }
        }
        getByName("nativeMain") {
            dependencies {
                api(libs.ktor.server.cio)
            }
        }
    }

    cocoapods {
        summary = "k2k"
        homepage = "homepage placeholder"
        ios.deploymentTarget = "13.5"
    }
}
