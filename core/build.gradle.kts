import java.util.Properties

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    android {
        namespace = "dev.logisim.mobile.core"
        compileSdk = 37
        minSdk = 26
        withHostTest {}
    }
    jvm()
    iosArm64()
    iosSimulatorArm64()
    if (providers.gradleProperty("enableMacosX64Ci").orNull == "true") macosX64()
    applyDefaultHierarchyTemplate()
    sourceSets {
        commonMain.dependencies { implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0") }
        commonTest.dependencies { implementation(kotlin("test")) }
        jvmMain.get().kotlin.srcDir("src/jvmAndAndroidMain/kotlin")
        androidMain.get().kotlin.srcDir("src/jvmAndAndroidMain/kotlin")
        if (providers.gradleProperty("enableMacosX64Ci").orNull == "true") {
            getByName("macosX64Main").kotlin.srcDir("src/iosMain/kotlin")
        }
    }
}

val headlessJvm = (kotlin.targets.getByName("jvm") as org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget)
    .compilations.getByName("main")

tasks.register<JavaExec>("runHeadless") {
    group = "application"
    description = "Runs a user-supplied .circ in temporal, truth-table, or stats mode."
    dependsOn("jvmJar")
    mainClass.set("dev.logisim.mobile.core.HeadlessCircuitCli")
    standardInput = System.`in`
    classpath(tasks.named("jvmJar"), headlessJvm.runtimeDependencyFiles)
}

val headlessIdentity = providers.fileContents(rootProject.layout.projectDirectory.file("app.properties")).asText.map { contents ->
    Properties().apply { contents.reader().use { load(it) } }
}

tasks.named<Jar>("jvmJar") {
    inputs.file(rootProject.file("app.properties"))
    manifest.attributes(
        "Implementation-Title" to headlessIdentity.get().getProperty("app.name"),
        "Implementation-Version" to headlessIdentity.get().getProperty("app.version"),
    )
}
