pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
rootProject.name = "k2k"

// k2k is the whole build. The example apps (presenter/desk/droid/ios) were removed along with the
// Discovery/Connection API they demonstrated — see the Scope section of README.md.
include("k2k")
