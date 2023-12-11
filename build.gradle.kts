buildscript {
    repositories {
        google()
        mavenCentral()
        mavenLocal()
        gradlePluginPortal()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.1.0")
    }
}

plugins {
    kotlin("jvm") version "2.0.0-Beta1"
    id("org.jetbrains.compose") version "1.5.11"
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
