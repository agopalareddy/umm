plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

kotlin {
    jvmToolchain(17)

    android {
        namespace = "io.github.agopalareddy.umm.core"
        compileSdk = 37
        minSdk = 29
        withHostTest {
            isIncludeAndroidResources = true
        }
    }
    jvm("desktop")

    sourceSets {
        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.kotlinx.serialization.json)
            api(libs.okhttp)
            implementation(libs.room.runtime)
            implementation(libs.room.ktx)
            api(libs.datastore.preferences)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
            implementation(libs.robolectric)
            implementation(libs.androidx.test.core)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.okhttp.mockwebserver)
            implementation(libs.turbine)
        }
    }
}

dependencies {
    add("kspAndroid", libs.room.compiler)
}

room {
    schemaDirectory("$projectDir/schemas")
}

tasks.withType<Test>().configureEach {
    // Live tests hit the real OpenRouter API and cost money; run them only with -Plive.
    if (!project.hasProperty("live")) exclude("**/live/**")
    listOf("sttModel", "cleanupModel").forEach { name ->
        project.findProperty(name)?.let { systemProperty(name, it) }
    }
}
