plugins {
    kotlin("multiplatform") version libs.versions.kotlin apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

group = "k2k"
version = "1.0.0"

allprojects {
    repositories {
        mavenCentral()
        google()
        gradlePluginPortal()
    }
}
