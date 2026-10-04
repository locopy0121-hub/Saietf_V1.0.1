import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    base
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

val junit4Dependency = libs.junit4

val androidModulePaths = setOf(
    ":app",
    ":core:database",
    ":core:designsystem",
    ":feature:dashboard",
    ":feature:portfolios",
    ":feature:transactions",
    ":feature:dividends",
    ":feature:editor",
    ":feature:settings",
    ":platform:surfaces",
)

subprojects {
    if (path !in androidModulePaths && buildFile.exists()) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")

        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(17)
        }

        dependencies.add("testImplementation", junit4Dependency)
    }
}
