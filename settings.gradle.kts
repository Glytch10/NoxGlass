pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.neoforged.net/releases/")
        maven("https://maven.kikugie.dev/releases/")
        maven("https://maven.kikugie.dev/snapshots/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("dev.kikugie.stonecutter") version "0.9.8"
}

rootProject.name = "JavaEditionMods"

val modTargets = mapOf(
    "fog" to listOf("26.2", "26.3"),
    "mru" to listOf("26.2", "26.3"),
    "sounds" to listOf("26.3"),
    "shields" to listOf("26.2", "26.3"),
    "glass" to listOf("1.21.1")
)

dependencyResolutionManagement {
    versionCatalogs {
        modTargets.values.flatten().distinct().forEach { minecraft ->
            create("mc${minecraft.replace('.', 'x')}") {
                from(files("gradle/minecraft/$minecraft.versions.toml"))
            }
        }
    }
}

stonecutter {
    create(rootProject) {
        modTargets.forEach { (mod, targets) ->
            branch(mod) {
                targets.forEach { minecraft ->
                    listOf("fabric", "neoforge").forEach { loader ->
                        version("$minecraft-$loader", minecraft.split('.').take(2).joinToString("."))
                            .buildscript("build.$loader.gradle.kts")
                    }
                }
            }
        }
        vcsVersion.set("26.2-fabric")
    }
}
