plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.dbus.java.core)
    implementation(libs.dbus.java.transport.junixsocket)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
