import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val appIdentity = Properties().apply {
    rootProject.file("app.properties").reader(Charsets.UTF_8).use { load(it) }
}

android {
    namespace = "dev.logisim.mobile"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }
    defaultConfig {
        applicationId = appIdentity.getProperty("app.androidId")
        minSdk = 26
        targetSdk = 36
        versionCode = appIdentity.getProperty("app.build").toInt()
        versionName = appIdentity.getProperty("app.version")
        testInstrumentationRunner = "dev.logisim.mobile.documents.DocumentIntegrationInstrumentation"
        resValue("string", "app_name", appIdentity.getProperty("app.name"))
    }
    buildTypes {
        create("e2e") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".e2e"
            versionNameSuffix = "-e2e"
            matchingFallbacks += "debug"
        }
    }
    testBuildType = providers.gradleProperty("androidTestBuildType").getOrElse("debug")
    buildFeatures {
        compose = true
        resValues = true
    }
    val keystorePath = providers.environmentVariable("LOGISIM_ANDROID_KEYSTORE").orNull
    if (keystorePath != null) {
        fun required(name: String): String = providers.environmentVariable(name).orNull
            ?: error("Set $name to configure release signing")
        fun secretFile(name: String): String = file(required(name)).readText().trimEnd('\r', '\n')
        signingConfigs {
            create("distribution") {
                storeFile = file(keystorePath)
                storePassword = secretFile("LOGISIM_ANDROID_STORE_PASSWORD_FILE")
                keyAlias = required("LOGISIM_ANDROID_KEY_ALIAS")
                keyPassword = secretFile("LOGISIM_ANDROID_KEY_PASSWORD_FILE")
            }
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("distribution")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":composeApp"))
    implementation("androidx.activity:activity-compose:1.13.0")
}
