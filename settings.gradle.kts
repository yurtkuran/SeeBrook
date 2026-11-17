rootProject.name = "seebrook"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

include(
    ":shared",
    ":server:db",
    ":server:platform",
    ":server:tenant",
    ":server:property",
    ":server:identity",
    ":server:document",
    ":server:finance",
    ":server:app",
)
