buildscript {
    repositories {
        google()
        mavenCentral()
        mavenLocal()
        gradlePluginPortal()
    }
    dependencies {
        classpath(libs.android.tools.gradle)
    }
}

plugins {
    kotlin("jvm") version libs.versions.kotlin
    id("org.jetbrains.compose") version libs.versions.jetpack.compose
}

group = "k2k"
version = "1.0.0"

repositories {
    google()
    gradlePluginPortal()
    mavenCentral()
    maven("https://jitpack.io")
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

allprojects {
    repositories {
        mavenCentral()
        google()
        gradlePluginPortal()
    }
}
