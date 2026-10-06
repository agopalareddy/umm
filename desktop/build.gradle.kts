plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvmToolchain(17)
}

compose.desktop {
    application {
        mainClass = "io.github.agopalareddy.umm.desktop.MainKt"
    }
}

// Bundles models/recommended.json, the offline fallback for model recommendations.
sourceSets.main {
    resources.srcDir("../models")
}

dependencies {
    implementation(project(":ui"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
