plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)

    android {
        namespace = "io.github.agopalareddy.umm.sync"
        compileSdk = 37
        minSdk = 29
        withHostTest {
            isIncludeAndroidResources = true
        }
    }
    jvm("desktop")

    // `-PrelayCheck` runs the one test that talks to the public relays; it is skipped otherwise.
    tasks.withType<Test>().configureEach {
        if (providers.gradleProperty("relayCheck").isPresent) {
            systemProperty("umm.relayCheck", "true")
            testLogging.showStandardStreams = true
            outputs.upToDateWhen { false }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            api(libs.secp256k1)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            api(libs.secp256k1.jni.android)
        }
        getByName("desktopMain").dependencies {
            api(libs.secp256k1.jni.jvm)
            api(libs.jmdns)
        }
        getByName("desktopTest").dependencies {
            implementation(libs.junit)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
            implementation(libs.okhttp.mockwebserver)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
            implementation(libs.robolectric)
            implementation(libs.androidx.test.core)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.secp256k1.jni.jvm)
        }
    }
}
