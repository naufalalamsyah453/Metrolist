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
    implementation(project(":shared"))
    // Desktop bundle (ui + foundation + animation + runtime) that also pulls
    // skiko-awt-runtime-linux-x64 — plain ui/foundation coordinates omit
    // libskiko-linux-x64.so and the app dies in SkiaLayer.<clinit>.
    // Pinned to linux-x64 because the shipped artifact is an x86_64 AppImage.
    implementation("org.jetbrains.compose.desktop:desktop-jvm-linux-x64:$composeMp")
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
