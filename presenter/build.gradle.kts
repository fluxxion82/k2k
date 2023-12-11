plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.native.cocoapods")
    id("com.google.devtools.ksp") version "2.0.0-Beta1-1.0.15"
    id("com.rickclephas.kmp.nativecoroutines") version "1.0.0-ALPHA-22-kotlin-2.0.0-Beta1"
}

group = "com.k2k.presenter"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    google()
    gradlePluginPortal()
    mavenCentral()
}

kotlin {
    targets {
        jvm()

        val iosTarget: (String, org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget.() -> Unit) -> org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget = when {
            System.getenv("SDK_NAME")?.startsWith("iphoneos") == true -> ::iosArm64
            System.getenv("NATIVE_ARCH")?.startsWith("arm") == true -> ::iosSimulatorArm64
            else -> ::iosX64
        }
        iosTarget("ios") {
            binaries.framework("CueDomain")
        }
    }

    sourceSets.all {
        languageSettings.optIn("kotlin.experimental.ExperimentalObjCName")
    }

    val ktorVersion = "3.0.0-beta-1"
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":k2k"))
                implementation("io.ktor:ktor-client-core:$ktorVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0-RC")
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
