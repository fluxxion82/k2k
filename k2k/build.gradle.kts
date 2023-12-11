plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.native.cocoapods")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.0-Beta1"
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
    targets {
        jvm {
            withJava()
            testRuns["test"].executionTask.configure {
                useJUnitPlatform()
            }
        }

        val iosTarget: (String, org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget.() -> Unit) -> org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget = when {
            System.getenv("SDK_NAME")?.startsWith("iphoneos") == true -> ::iosArm64
            System.getenv("NATIVE_ARCH")?.startsWith("arm") == true -> ::iosSimulatorArm64
            else -> ::iosX64
        }
        iosTarget("ios") {
            binaries.framework("k2k")
        }
    }


    val ktorVersion = "3.0.0-beta-1"
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-core:$ktorVersion")
                implementation("io.ktor:ktor-network:$ktorVersion")
                implementation("io.ktor:ktor-network-tls:$ktorVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.4.1")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0-RC")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val jvmMain by getting
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
