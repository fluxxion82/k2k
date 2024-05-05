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
    jvm {
        withJava()
    }

    val iosTarget: (String, org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget.() -> Unit) -> org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget = when {
        System.getenv("SDK_NAME")?.startsWith("iphoneos") == true -> ::iosArm64
        System.getenv("NATIVE_ARCH")?.startsWith("arm") == true -> ::iosSimulatorArm64
        else -> ::iosX64
    }
    iosTarget("ios") {
        binaries.framework("k2k")
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.network)
                implementation(libs.ktor.network.tls)
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization)

                implementation("io.ktor:ktor-client-cio:3.0.0-beta-1")
                implementation("io.ktor:ktor-client-content-negotiation:3.0.0-beta-1")
                implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.0-beta-1")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation("io.ktor:ktor-server-content-negotiation:3.0.0-beta-1")
                implementation("io.ktor:ktor-server-core:3.0.0-beta-1")
                api("io.ktor:ktor-server-netty:3.0.0-beta-1")
            }
        }
        val jvmTest by getting
        val iosMain by getting
        val iosTest by getting
    }

    cocoapods {
        summary = "k2k"
        homepage = "homepage placeholder"
        ios.deploymentTarget = "13.5"
    }
}
