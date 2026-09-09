pluginManagement {
    repositories {
        maven("https://maven.kikugie.dev/releases")
        maven("https://maven.fabricmc.net/")
        maven("https://maven.architectury.dev/")
        maven("https://maven.neoforged.net/releases/")
        gradlePluginPortal()
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.8"
    // The matrix needs JDK 17, 21 and 25; download whichever are missing.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

stonecutter {
    kotlinController = true
    centralScript = "build.gradle.kts"
    create(rootProject) {
        // Node name is "<minecraft>-<loader>"; the second argument is the Minecraft
        // version that //? if predicates and stonecutter.eval compare against.
        version("1.20.1-fabric", "1.20.1")
        version("1.20.1-forge", "1.20.1")
        version("1.21.1-fabric", "1.21.1")
        version("1.21.1-neoforge", "1.21.1")
        version("26.2-fabric", "26.2")
        version("26.2-neoforge", "26.2")
        version("26.3-fabric", "26.3")
        vcsVersion = "1.21.1-fabric"
    }
}

rootProject.name = "better-blending"
