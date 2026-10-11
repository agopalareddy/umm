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
                // dbus-java's login to the session bus (com.sun.security.auth.module.UnixSystem).
                "jdk.security.auth",
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

tasks.register<Tar>("packageLinuxTarball") {
    group = "distribution"
    description = "Packages the Linux standalone tarball."
    dependsOn("createDistributable")
    compression = Compression.GZIP
    archiveFileName.set("umm-$appVersionName-linux-x86_64.tar.gz")
    destinationDirectory.set(layout.buildDirectory.dir("dist"))
    into("umm-$appVersionName") {
        from(layout.buildDirectory.dir("compose/binaries/main/app/Umm")) {
            into("Umm")
            eachFile {
                if (path.contains("/bin/")) {
                    filePermissions { unix(493) }
                }
            }
        }
        from(rootProject.file("packaging/linux/install.sh")) {
            filePermissions { unix(493) }
        }
        from(rootProject.file("packaging/linux/uninstall.sh")) {
            filePermissions { unix(493) }
        }
        from(rootProject.file("LICENSE.md"))
        into("share") {
            from(rootProject.file("packaging/linux/$appId.desktop.in"))
            from(rootProject.file("packaging/linux/$appId.svg"))
            from(rootProject.file("packaging/linux/$appId.png"))
            from(rootProject.file("packaging/linux/$appId.metainfo.xml"))
        }
    }
}

fun registerNfpmPackageTask(packager: String, outputFileName: String, taskName: String) =
    tasks.register<Exec>(taskName) {
        group = "distribution"
        description = "Packages the Linux $packager package using nfpm."
        dependsOn("createDistributable")

        val distDir = layout.buildDirectory.dir("dist").get().asFile
        val packagingDir = layout.buildDirectory.dir("packaging").get().asFile
        val systemDesktopFile = File(packagingDir, "$appId.desktop")

        doFirst {
            val nfpmFound = runCatching {
                val p = ProcessBuilder("which", "nfpm").start()
                p.waitFor() == 0
            }.getOrElse { false }
            if (!nfpmFound) {
                throw GradleException("nfpm executable not found on PATH. Install nfpm to package $packager.")
            }
            distDir.mkdirs()
            packagingDir.mkdirs()
            systemDesktopFile.writeText(
                rootProject.file("packaging/linux/$appId.desktop.in")
                    .readText()
                    .replace("@EXEC@", "/opt/umm/bin/Umm"),
            )
        }

        workingDir = rootProject.projectDir
        executable = "nfpm"
        args(
            "pkg",
            "--config",
            rootProject.file("packaging/linux/nfpm.yaml").absolutePath,
            "--packager",
            packager,
            "--target",
            File(distDir, outputFileName).absolutePath,
        )
        environment("VERSION", appVersionName)
    }

registerNfpmPackageTask("deb", "umm_${appVersionName}_amd64.deb", "packageLinuxDeb")
registerNfpmPackageTask("rpm", "umm-$appVersionName-1.x86_64.rpm", "packageLinuxRpm")

// Bundles models/recommended.json, the offline fallback for model recommendations.
sourceSets.main {
    resources.srcDir("../models")
}

dependencies {
    implementation(project(":sync"))
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
