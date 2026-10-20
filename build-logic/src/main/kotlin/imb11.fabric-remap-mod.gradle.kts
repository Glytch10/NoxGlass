import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    id("net.fabricmc.fabric-loom-remap")
    id("imb11.mod")
}

val targetMinecraftVersion = project.minecraftVersion
val metadata = modMetadata

dependencies {
    "minecraft"("com.mojang:minecraft:$targetMinecraftVersion")
    "mappings"(loom.officialMojangMappings())
    "modImplementation"(mcLibrary("fabric-loader"))
    "modImplementation"(mcLibrary("fabric-api"))
}

loom {
    mods {
        register(metadata.id) {
            sourceSet(sourceSets["main"])
        }
    }
    runs {
        if (metadata.optional("mod.environment") == "client") {
            remove(getByName("server"))
        }
        configureEach {
            isIdeConfigGenerated = true
            configName = "${metadata.required("mod.name")} - $targetMinecraftVersion - Fabric - ${name.replaceFirstChar { it.titlecase() }}"
            ideConfigFolder.set(metadata.required("mod.name"))
            runDir(rootProject.file("run/${metadata.id}/$targetMinecraftVersion/fabric/$name").relativeTo(project.projectDir).invariantSeparatorsPath)
        }
    }
}

if (metadata.optional("mod.datagen").toBoolean()) {
    fabricApi {
        configureDataGeneration {
            outputDirectory = requireNotNull(project.parent).file("src/generated/$targetMinecraftVersion")
            addToResources = false
            client = true
        }
    }

    loom.runs.named("datagen") {
        configName = "${metadata.required("mod.name")} - $targetMinecraftVersion - Fabric - Data Generation"
    }
}

tasks.named<ProcessResources>("processResources") {
    exclude("META-INF/neoforge.mods.toml", "META-INF/mods.toml", "META-INF/accesstransformer.cfg", "interfaces.json")
}
