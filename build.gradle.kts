import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    base
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

val junit4Dependency = libs.junit4

subprojects {
    if (path != ":app" && buildFile.exists()) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")

        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(17)
        }

        dependencies.add("testImplementation", junit4Dependency)
    }
}
