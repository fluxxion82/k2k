import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.0.0-Beta1"
    id("org.jetbrains.compose") version "1.5.11"
}

group = "com.k2k.desk"
version = "1.0.0"

repositories {
    google()
    gradlePluginPortal()
    mavenCentral()
    maven("https://jitpack.io")
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

compose.desktop {
    application {
        mainClass = "MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "desktopTest"
            disableDefaultConfiguration()
            fromFiles(project.fileTree("libs/") { include("**/*.jar") })
            mainJar.set(project.file("main.jar"))
            dependsOn("mainJarTask")
        }
    }
}

dependencies {
    implementation(project(":k2k"))
    implementation(project(":presenter"))

    implementation(compose.desktop.currentOs)
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}
