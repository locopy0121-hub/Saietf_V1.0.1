plugins {
    alias(libs.plugins.android.application)
}

val approvedModules = rootProject.subprojects
    .filter { it.buildFile.exists() }
    .map { it.path }
    .sorted()
    .joinToString(",")

android {
    namespace = "tw.saietf.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "tw.saietf.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 10002
        versionName = "1.0.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "INCLUDED_MODULES", "\"$approvedModules\"")
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation(libs.junit4)
}
