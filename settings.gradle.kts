pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SaiETF"

include(
    ":app",
    ":core:model",
    ":core:finance",
    ":core:database",
    ":core:market",
    ":core:designsystem",
    ":feature:dashboard",
    ":feature:portfolios",
    ":feature:transactions",
    ":feature:dividends",
    ":feature:editor",
    ":feature:settings",
    ":platform:surfaces",
)
