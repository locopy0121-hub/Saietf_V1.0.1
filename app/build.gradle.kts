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

    signingConfigs {
        create("development") {
            storeFile = file("signing/saietf-development.jks")
            storePassword = "saietf-dev-only-2026"
            keyAlias = "saietf-development"
            keyPassword = "saietf-dev-only-2026"
        }
    }

    defaultConfig {
        applicationId = "tw.saietf.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 10017
        versionName = "1.0.17"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "INCLUDED_MODULES", "\"$approvedModules\"")
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("development")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core:database"))
    implementation(project(":core:finance"))
    implementation(project(":core:model"))
    implementation(project(":core:market"))
    testImplementation(libs.junit4)
}
