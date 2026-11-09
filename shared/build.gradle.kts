plugins {
    alias(libs.plugins.kotlin.serialization)
    // KGP + serialization plugin versions come from the root project.
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
    sourceSets {
        main {
            // The :innertube facade is pure JVM Kotlin (no android.* imports);
            // compile the same sources here for desktop instead of forking them.
            // Android keeps building them through the unchanged :innertube module.
            kotlin.srcDir("${rootDir}/innertube/src/main/kotlin")
        }
    }
}

dependencies {
    api(libs.innertubex)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.encoding)
    // No libs.timber here: Android-only artifact. shared/src/main carries a
    // minimal timber.log.Timber stub (see timber/log/Timber.kt).
    testImplementation(libs.junit)
    testImplementation(libs.ktor.client.mock)
}
