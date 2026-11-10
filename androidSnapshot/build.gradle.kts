import java.util.Properties

plugins {
    id("com.android.application")
}

val appIdentity = Properties().apply {
    rootProject.file("app.properties").reader(Charsets.UTF_8).use { load(it) }
}

android {
    namespace = "dev.logisim.mobile.snapshot"
    compileSdk = 37
    defaultConfig {
        applicationId = appIdentity.getProperty("app.androidId") + ".snapshot.e2e.test"
        minSdk = 26
        targetSdk = 36
        versionCode = appIdentity.getProperty("app.build").toInt()
        versionName = appIdentity.getProperty("app.version")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
