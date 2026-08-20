plugins {
    kotlin("multiplatform")
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization) version libs.versions.kotlin
}

group = "com.k2k"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    google()
    gradlePluginPortal()
}

kotlin {
    applyDefaultHierarchyTemplate()
    // The Android target uses com.android.kotlin.multiplatform.library, NOT com.android.library:
    // AGP 9 rejects com.android.library alongside the Kotlin Multiplatform plugin outright. The
    // block is `android { }` inside `kotlin { }` — it was `androidLibrary { }` below AGP 8.12.0 and
    // is deprecated from 9.1.0-alpha09, per
    // https://developer.android.com/kotlin/multiplatform/plugin. Minimums: AGP 8.10.0, KGP 2.0.0.
    //
    // Restoring this was a prerequisite for moviePicker, which calls NetworkScanner(androidContext())
    // — only the android actual takes a Context; the jvm one is a no-arg TODO(). Both consumers are
    // on AGP 9.3.1, and both provide the android-kotlin-multiplatform-library catalog key this
    // plugin alias resolves against.
    android {
        namespace = "com.k2k"
        compileSdk = 36
        // minSdk 26, not higher: java.nio.file (Files, PosixFilePermissions) arrived on Android in
        // API 26, and createUploadTempFile in com.k2k.test.server uses it to keep upload temp files
        // owner-only. 26 is therefore the real floor for this library.
        //
        // Do not raise it casually. A library minSdk above a consumer's breaks their build outright
        // ("minSdkVersion N cannot be smaller than version M declared in library"), and moviePicker
        // consumes :k2k from data/remote at minSdk 26. Passman is at 31 and unaffected either way.
        minSdk = 26
    }

    jvm()

    // iosX64 is deliberately absent: it existed only for the deleted :presenter module, which
    // resolved its target from SDK_NAME/NATIVE_ARCH and fell back to iosX64 outside Xcode. Both
    // real consumers (passman, moviePicker) build frameworks for iosArm64 + iosSimulatorArm64 only.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        iosTarget.binaries.framework("k2k") {
            isStatic = true
        }
    }

    sourceSets {
        getByName("commonMain") {
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

                implementation(libs.ktor.server.content.negotiation)
                implementation(libs.ktor.server.core)

            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        // jvmSources is shared by the jvm and android targets: both run the Netty/CIO server and
        // the OkHttp client, native can do neither. Named source sets use getByName/create rather
        // than the by getting / by creating delegates, which Gradle 9.0 deprecated.
        //
        // The explicit dependsOn(commonMain) is required: without it KGP warns that this set is in
        // the jvm main compilation but has no path to commonMain, and once the android target
        // returns it would compile against its own declared dependencies only.
        //
        // ALL of com.k2k.test.* lives here — tls, client, and server — and must stay here. Passman
        // compiles against com.k2k.test.{server,client,tls} from its own jvmAndAndroidMain, which
        // today resolves the jvm variant only because k2k has no android target. The moment one
        // exists, Passman's Android compilation resolves the android variant instead, and anything
        // left in jvmMain simply is not there. That is a compile failure in a downstream app, not a
        // behaviour change — so treat this source set as part of k2k's contract with its consumers.
        val jvmSources = create("jvmSources") {
            dependsOn(getByName("commonMain"))
            dependencies {
                api(libs.ktor.server.netty)
                api(libs.ktor.server.cio)
                implementation(libs.ktor.client.okhttp)
            }
        }
        getByName("jvmMain") {
            dependsOn(jvmSources)
        }
        // androidMain shares jvmSources with jvmMain: com.k2k.test.* is JVM-library code that runs
        // unchanged on Android (Passman ships Netty in its APK today and syncs on real devices).
        getByName("androidMain") {
            dependsOn(jvmSources)
        }
        getByName("nativeMain") {
            dependencies {
                api(libs.ktor.server.cio)

                // X.509 construction for on-device identity generation. iOS has no API that creates
                // or signs a certificate — the whole SecCertificate* surface only parses — so this
                // fills a gap that exists nowhere else.
                //
                // Scoped to nativeMain, NOT commonMain, on purpose. On JVM it would pull
                // BouncyCastle (bcpkix -> bcutil -> bcprov) into every consumer's graph, and the JVM
                // needs none of it: identities arrive there as PKCS#12. Passman in particular pins
                // bcprov with `strictly` to keep a specific patch release, and adding a transitive
                // request against that is a risk taken for nothing.
                //
                // Declared with a literal coordinate rather than through `libs`, also on purpose:
                // k2k is included as a subproject by both consumers, so `libs` resolves against
                // THEIR catalog. An alias here would become a key every consumer must define, at a
                // version this project does not control.
                implementation("at.asitplus.signum:indispensable:3.26.0")

                // The Darwin engine is the ONLY way to do mutual TLS from Kotlin/Native. CIO and
                // raw ktor-network cannot: their nonJvm openTLSSession is literally
                // error("TLS sessions are not supported on Native platform."), so Socket.tls()
                // compiles on iOS and throws at runtime.
                //
                // Declared here rather than through `libs` because passman's catalog has no
                // ktor-client-darwin key, so an alias would fail their configuration outright.
                // The BOM is what keeps this version-locked to the rest of Ktor: these artifacts
                // must agree, and a darwin client pinned beside a consumer-supplied core is exactly
                // the skew that catalog coupling already caused once with Ktor 3.0.1 vs 3.5.2.
                implementation(project.dependencies.platform("io.ktor:ktor-bom:3.5.2"))
                implementation("io.ktor:ktor-client-darwin")
            }
        }
    }

}

// Every jvmTest class stands up a real Netty listener on a real socket. Sharing one JVM lets one
// class's teardown disturb the next — a server stopped while a connection is still mid-timeout is
// the worst case, and it surfaces as an unrelated class failing perhaps one run in eight. A fresh
// JVM per class costs a few seconds and buys tests that fail only for their own reasons.
tasks.withType<Test>().configureEach {
    forkEvery = 1
}
