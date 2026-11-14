import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

// The JVM target only captures canvas-recorded exports (Skia, like iOS). Shared tests keep running on the Android
// host and iOS; on the JVM, Compose's Skia implementation is registered by the host tests themselves.
tasks.matching { it.name == "jvmTest" }.configureEach {
    (this as Test).filter.includeTestsMatching("dev.logisim.mobile.*HostTest")
    providers.gradleProperty("uiScreenshots").orNull?.let { systemProperty("ui.screenshots", it) }
}

tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    standalone.set(false)
    providers.gradleProperty("iosTestDevice").orNull?.let { device.set(it) }
}

kotlin {
    android {
        namespace = "dev.logisim.mobile.ui"
        compileSdk {
            version = release(37) {
                minorApiLevel = 1
            }
        }
        minSdk = 26
        androidResources.enable = true
        withHostTest {}
    }
    // Test-only host target: Skia drawing like iOS, so canvas-recorded exports run without a simulator.
    jvm()
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation("org.jetbrains.compose.runtime:runtime:1.13.0-beta02")
            implementation("org.jetbrains.compose.foundation:foundation:1.13.0-beta02")
            implementation("org.jetbrains.compose.ui:ui:1.13.0-beta02")
            implementation("org.jetbrains.compose.material3:material3:1.9.0")
            implementation("org.jetbrains.compose.components:components-resources:1.13.0-beta02")
            implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
        }
        androidMain.dependencies {
            implementation("androidx.activity:activity-compose:1.13.0")
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
            // Compose UI tests and screenshots of the shared editor on the Skia host (UI/UX sweep evidence).
            implementation("org.jetbrains.compose.ui:ui-test:1.13.0-beta02")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
        }
        iosTest.dependencies { implementation("org.jetbrains.compose.ui:ui-test:1.13.0-beta02") }
    }
}
