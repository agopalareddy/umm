plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvmToolchain(17)
}

val appVersionName = providers.gradleProperty("versionName").getOrElse("0.1.0-dev")
val packageVersionDigits = appVersionName.substringBefore("-")
version = appVersionName

tasks.withType<Jar>().configureEach {
    manifest {
        attributes("Implementation-Version" to appVersionName)
    }
}

compose.desktop {
    application {
        mainClass = "io.github.agopalareddy.umm.desktop.MainKt"
        nativeDistributions {
            packageName = "Umm"
            packageVersion = packageVersionDigits
            modules(
                "java.desktop",
                "jdk.crypto.ec",
                "jdk.unsupported",
                "java.naming",
                "jdk.charsets",
            )
        }
    }
}

// Dev install: puts the runnable image in the user's application menu with an icon, so the
// portals can identify the app. Packaging replaces this.
val appId = "io.github.agopalareddy.Umm"
val dataHome = providers.environmentVariable("XDG_DATA_HOME").orElse(providers.systemProperty("user.home").map { "$it/.local/share" })
val configHome = providers.environmentVariable("XDG_CONFIG_HOME").orElse(providers.systemProperty("user.home").map { "$it/.config" })

fun run(vararg command: String) {
    // update-desktop-database is a convenience; a missing one must not fail the install.
    runCatching { ProcessBuilder(*command).inheritIO().start().waitFor() }
}

tasks.register("installDev") {
    group = "distribution"
    description = "Installs the runnable image into the user's application menu."
    dependsOn("createDistributable")
    doLast {
        val launcher = layout.buildDirectory.file("compose/binaries/main/app/Umm/bin/Umm").get().asFile
        check(launcher.canExecute()) { "missing launcher: $launcher" }
        val applications = File(dataHome.get(), "applications").apply { mkdirs() }
        val icons = File(dataHome.get(), "icons/hicolor/scalable/apps").apply { mkdirs() }
        rootProject.file("packaging/linux/$appId.svg").copyTo(File(icons, "$appId.svg"), overwrite = true)
        File(applications, "$appId.desktop").writeText(
            rootProject.file("packaging/linux/$appId.desktop.in").readText().replace("@EXEC@", "\"${launcher.absolutePath}\""),
        )
        run("update-desktop-database", applications.path)
        println("Installed $appId; launch Umm from the application menu.")
    }
}

tasks.register("uninstallDev") {
    group = "distribution"
    description = "Removes what installDev installed, and the start-at-login entry."
    doLast {
        val applications = File(dataHome.get(), "applications")
        File(applications, "$appId.desktop").delete()
        File(dataHome.get(), "icons/hicolor/scalable/apps/$appId.svg").delete()
        File(configHome.get(), "autostart/$appId.desktop").delete()
        run("update-desktop-database", applications.path)
        println("Removed $appId.")
    }
}

// Bundles models/recommended.json, the offline fallback for model recommendations.
sourceSets.main {
    resources.srcDir("../models")
}

dependencies {
    implementation(project(":ui"))
    implementation(project(":linux"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.material.color.utilities)
    implementation(libs.dbus.java.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
