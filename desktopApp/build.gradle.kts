import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    // KGP rides on the root buildscript classpath (version from libs.versions.toml).
    kotlin("jvm")
}

val composeMp = libs.versions.composeMultiplatform.get()

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("org.jetbrains.compose.runtime:runtime:$composeMp")
    implementation("org.jetbrains.compose.foundation:foundation:$composeMp")
    implementation("org.jetbrains.compose.ui:ui:$composeMp")
    implementation("org.jetbrains.compose.components:components-resources:$composeMp")
    implementation("org.jetbrains.compose.material3:material3:${libs.versions.composeMaterial3.get()}")
}

compose.desktop {
    application {
        mainClass = "com.metrolist.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Deb)
            packageName = "Metrolist"
            packageVersion = "13.7.0"
            description = "YouTube Music client"
            vendor = "Metrolist contributors"
            // AppImage is assembled in CI (packaging/appimage.sh) from the uber jar;
            // the Deb target stays available for local `packageDeb` runs.
        }
    }
}
