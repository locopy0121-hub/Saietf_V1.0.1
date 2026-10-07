plugins {
    alias(libs.plugins.android.application)
}

val developmentSigningPassword = listOf("saietf-dev", "-only-", "2026").joinToString("")

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
            storePassword = developmentSigningPassword
            keyAlias = "saietf-development"
            keyPassword = developmentSigningPassword
        }
    }

    defaultConfig {
        applicationId = "tw.saietf.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 10132
        versionName = "1.1.32"
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
    implementation(libs.androidx.activity)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.square.okhttp)
    testImplementation(libs.junit4)
}
