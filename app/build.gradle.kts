import java.io.IOException
import java.util.Properties
import java.util.zip.ZipFile

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
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":sync"))
    implementation(project(":core"))
    implementation(project(":ui"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.play.services.code.scanner)
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
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.secp256k1.jni.jvm)
}

// Newer Android devices can run with 16 KB memory pages, where a 64-bit native library aligned to 4 KB does not load.
tasks.register("check16KbAlignment") {
    group = "verification"
    description = "Fails when a 64-bit native library in the debug APK is not aligned for 16 KB pages."
    dependsOn("assembleDebug")
    val apk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")
    val scratch = layout.buildDirectory.dir("check16Kb")
    doLast {
        val dir = scratch.get().asFile.apply {
            deleteRecursively()
            mkdirs()
        }
        val tooSmall = mutableListOf<String>()
        var checked = 0
        ZipFile(apk.get().asFile).use { zip ->
            val libraries = zip.entries().asSequence().filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
            for (entry in libraries) {
                val abi = entry.name.split('/')[1]
                if (abi != "arm64-v8a" && abi != "x86_64") continue
                val file = File(dir, entry.name.replace('/', '_'))
                zip.getInputStream(entry).use { input -> file.outputStream().use { input.copyTo(it) } }
                val process = try {
                    ProcessBuilder("readelf", "-lW", file.path).redirectErrorStream(true).start()
                } catch (e: IOException) {
                    throw GradleException("check16KbAlignment needs readelf (binutils) on the PATH", e)
                }
                val output = process.inputStream.bufferedReader().readText()
                if (process.waitFor() != 0) throw GradleException("readelf failed on ${entry.name}: $output")
                val alignments = output.lineSequence()
                    .filter { it.trimStart().startsWith("LOAD") }
                    .map { it.trim().substringAfterLast(' ').removePrefix("0x").toLong(16) }
                    .toList()
                if (alignments.isEmpty()) throw GradleException("no LOAD segments found in ${entry.name}")
                val smallest = alignments.min()
                println("${entry.name}: LOAD alignment 0x${smallest.toString(16)}")
                checked++
                if (smallest < 0x4000) tooSmall += "${entry.name} (0x${smallest.toString(16)})"
            }
        }
        if (tooSmall.isNotEmpty()) throw GradleException("not aligned for 16 KB pages: $tooSmall")
        println("$checked native libraries checked")
    }
}
