plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.native.cocoapods")
    alias(libs.plugins.native.coroutines)
}

group = "com.k2k.presenter"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    google()
    gradlePluginPortal()
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
        binaries.framework("presenter")
    }

    sourceSets.all {
        languageSettings.optIn("kotlin.experimental.ExperimentalObjCName")
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":k2k"))
                implementation(libs.ktor.client.core)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }

    cocoapods {
        summary = "k2kPresenter"
        homepage = "homepage placeholder"
        ios.deploymentTarget = "13.5"
    }
}

kotlin {
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget> {
        compilations.get("main").kotlinOptions.freeCompilerArgs += "-Xexport-kdoc"
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs = listOf("-Xuse-experimental=kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
}
