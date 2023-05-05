buildscript {
    repositories {
        google()
        mavenCentral()
        mavenLocal()
        gradlePluginPortal()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:7.4.2")
    }
}

plugins {
    kotlin("jvm") version "1.8.20"
    kotlin("kapt") version "1.8.20"
    id("org.jetbrains.compose") version "1.4.0"
    kotlin("plugin.serialization") version "1.8.10"
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
        mavenCentral()
        maven { setUrl("https://repo.repsy.io/mvn/chrynan/public") }
    }
}
