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
        nativeDistributions {
            packageName = "Umm"
            packageVersion = "1.0.0"
            // dbus-java and javax.sound pull in modules the plugin's usage scan misses.
            includeAllModules = true
        }
    }
}

// Dev install: puts the runnable image in the user's application menu with the umm:// handler and an icon, so the
// portals can identify the app and the browser sign-in can reach it. Packaging (sub-project 4) replaces this.
val appId = "io.github.agopalareddy.Umm"
val dataHome = providers.environmentVariable("XDG_DATA_HOME").orElse(providers.systemProperty("user.home").map { "$it/.local/share" })
val configHome = providers.environmentVariable("XDG_CONFIG_HOME").orElse(providers.systemProperty("user.home").map { "$it/.config" })

fun run(vararg command: String) {
    // update-desktop-database and xdg-mime are conveniences; a missing one must not fail the install.
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
        file("packaging/$appId.svg").copyTo(File(icons, "$appId.svg"), overwrite = true)
        File(applications, "$appId.desktop").writeText(
            file("packaging/$appId.desktop.in").readText().replace("@EXEC@", launcher.absolutePath),
        )
        run("update-desktop-database", applications.path)
        run("xdg-mime", "default", "$appId.desktop", "x-scheme-handler/umm")
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

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
