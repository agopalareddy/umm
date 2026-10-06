plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvmToolchain(17)

    android {
        namespace = "io.github.agopalareddy.umm.ui"
        compileSdk = 37
        minSdk = 29
    }
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            api(libs.jb.compose.runtime)
            api(libs.jb.compose.foundation)
            api(libs.jb.compose.ui)
            api(libs.jb.material3)
            api(libs.jb.material.icons.extended)
            api(libs.jb.navigation.compose)
            api(libs.jb.lifecycle.runtime.compose)
        }
        getByName("desktopTest").dependencies {
            implementation(libs.junit)
        }
    }
}
