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
    jvm()

    listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        iosTarget.binaries.framework("k2k")
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

                implementation(libs.ktor.client.cio)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(libs.ktor.server.content.negotiation)
                implementation(libs.ktor.server.core)
                api(libs.ktor.server.netty)
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
