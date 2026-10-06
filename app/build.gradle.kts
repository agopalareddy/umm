import java.util.Properties

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

/**
 * Release signing: CI passes UMM_KEYSTORE_* environment variables; locally the key lives outside the repo in
 * ~/.config/umm/keystore.properties. Without either, release builds are unsigned.
 */
val releaseSigning: Map<String, String>? = run {
    val env = System.getenv()
    if (env["UMM_KEYSTORE_FILE"] != null) {
        mapOf(
            "storeFile" to env.getValue("UMM_KEYSTORE_FILE"),
            "storePassword" to env.getValue("UMM_KEYSTORE_PASSWORD"),
            "keyAlias" to env.getValue("UMM_KEY_ALIAS"),
            "keyPassword" to env.getValue("UMM_KEY_PASSWORD"),
        )
    } else {
        val file = File(System.getProperty("user.home"), ".config/umm/keystore.properties")
        if (!file.exists()) null
        else Properties().apply { file.inputStream().use(::load) }
            .let { props -> props.stringPropertyNames().associateWith { props.getProperty(it) } }
    }
}

// Release versions come from the git tag in CI (-PversionName=1.2.3 -PversionCode=10203).
val appVersionName = providers.gradleProperty("versionName").getOrElse("0.1.0-dev")
val appVersionCode = providers.gradleProperty("versionCode").map(String::toInt).getOrElse(1)

android {
    namespace = "io.github.agopalareddy.umm"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.agopalareddy.umm"
        minSdk = 29
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }
    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getValue("storeFile"))
                storePassword = releaseSigning.getValue("storePassword")
                keyAlias = releaseSigning.getValue("keyAlias")
                keyPassword = releaseSigning.getValue("keyPassword")
            }
        }
    }
    buildTypes {
        debug {
            buildConfigField("String", "DEBUG_OPENROUTER_API_KEY", "\"$debugApiKey\"")
            manifestPlaceholders["appLabel"] = "Umm"
        }
        release {
            buildConfigField("String", "DEBUG_OPENROUTER_API_KEY", "\"\"")
            manifestPlaceholders["appLabel"] = "Umm"
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseSigning != null) signingConfig = signingConfigs.getByName("release")
        }
        // The shrunk release code under its own app id, so it installs next to a debug build for testing.
        create("staging") {
            initWith(getByName("release"))
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-staging"
            buildConfigField("String", "DEBUG_OPENROUTER_API_KEY", "\"$debugApiKey\"")
            manifestPlaceholders["appLabel"] = "Umm staging"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
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
    implementation(project(":ui"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.browser)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
}
