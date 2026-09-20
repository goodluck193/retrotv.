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
rootProject.name = "RetroTV"
include(":app")
check(file(".engine/libretrodroid/build.gradle.kts").exists()) {
    "Run python3 scripts/prepare_engine.py before opening/building RetroTV."
}
include(":emulation")
project(":emulation").projectDir = file(".engine/libretrodroid")
