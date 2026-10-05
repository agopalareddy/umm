plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass = "io.github.agopalareddy.umm.desktop.MainKt"
}

// Bundles models/recommended.json, the offline fallback for model recommendations.
sourceSets.main {
    resources.srcDir("../models")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}
