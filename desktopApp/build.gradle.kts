import com.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    // KGP rides on the root buildscript classpath (version from libs.versions.toml).
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.ui)
    implementation(compose.components.resources)
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
