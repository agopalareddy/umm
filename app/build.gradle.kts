plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/** Reads KEY=value pairs from the repo-root .env (quotes stripped, # comments ignored). */
fun readDotEnv(file: File): Map<String, String> =
    if (!file.exists()) emptyMap()
    else file.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
        .associate { line ->
            val key = line.substringBefore('=').trim()
            val value = line.substringAfter('=').trim().removeSurrounding("\"").removeSurrounding("'")
            key to value
        }

val debugApiKey = readDotEnv(rootProject.file(".env"))["OPENROUTER_API_KEY"].orEmpty()

android {
    namespace = "io.github.agopalareddy.umm"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.agopalareddy.umm"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    buildTypes {
        debug {
            buildConfigField("String", "DEBUG_OPENROUTER_API_KEY", "\"$debugApiKey\"")
        }
        release {
            buildConfigField("String", "DEBUG_OPENROUTER_API_KEY", "\"\"")
            isMinifyEnabled = false
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        getByName("main").assets.srcDir("../models")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.browser)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
}
