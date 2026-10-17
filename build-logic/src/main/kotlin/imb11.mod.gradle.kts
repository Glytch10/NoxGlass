import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.language.jvm.tasks.ProcessResources
import java.util.Properties

plugins {
    `java-library`
    `maven-publish`
    id("dev.kikugie.postprocess.jsonlang")
}

val metadata = readModMetadata()
extensions.add("modMetadata", metadata)
metadata.values.forEach { (key, value) -> extensions.extraProperties[key] = value }

val catalog = minecraftCatalog
val minecraftVersion = project.minecraftVersion
val loader = project.name.substringAfterLast('-')
val moduleDirectory = requireNotNull(project.parent).layout.projectDirectory
val javaVersion = catalog.findVersion("java").map { it.requiredVersion.toInt() }
    .orElseGet { providers.gradleProperty("javaVersion").get().toInt() }
val releaseVersion = "${metadata.version}+$minecraftVersion"

group = metadata.required("mod.group")
version = "$releaseVersion-$loader"
base.archivesName.set(metadata.id)

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion))
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = metadata.optional("publish.mavenGroup").ifBlank { metadata.required("mod.group") }
            artifactId = "${metadata.id}-$loader"
            version = releaseVersion
            from(components["java"])
            pom {
                name.set(metadata.required("mod.name"))
                description.set(metadata.required("mod.description"))
                licenses {
                    license {
                        name.set(metadata.required("mod.license"))
                    }
                }
            }
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(javaVersion)
    dependsOn("stonecutterGenerate")
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.withType<Jar>().configureEach {
    from(moduleDirectory.file("LICENSE")) {
        rename { "LICENSE_${metadata.id}" }
    }
}

val requiredMod = metadata.list("mod.required").firstOrNull()
val requiredModVersion = requiredMod?.let { mod ->
    val contents = providers.fileContents(rootProject.layout.projectDirectory.file("$mod/mod.properties")).asText.get()
    Properties().apply { contents.reader().use(::load) }.getProperty("mod.version")
}.orEmpty()
val targetMinecraft = when {
    minecraftVersion == "26.1.2" -> if (loader == "fabric") ">=26.1 <=26.1.2" else "[26.1,26.1.2]"
    loader == "fabric" -> "=$minecraftVersion"
    else -> "[$minecraftVersion]"
}
val yaclVersion = catalog.findLibrary("yacl-$loader")
    .map { it.get().versionConstraint.requiredVersion.removeSuffix("-$loader") }
    .orElse("")
val resourceProperties = mapOf(
    "version" to releaseVersion,
    "mod_version" to metadata.version,
    "minecraft" to targetMinecraft,
    "target_minecraft" to targetMinecraft,
    "mod_id" to metadata.id,
    "mod_name" to metadata.required("mod.name"),
    "mod_description" to metadata.required("mod.description"),
    "mod_license" to metadata.required("mod.license"),
    "loader" to loader,
    "fml_loader" to "[4,)",
    "target_loader" to if (loader == "fabric") catalog.findVersion("fabric-loader").get().requiredVersion
        else "[${catalog.findVersion("neoforge").get().requiredVersion},)",
    "target_fabricloader" to catalog.findVersion("fabric-loader").get().requiredVersion,
    "target_architectury" to catalog.findVersion("architectury").map { it.requiredVersion }.orElse(""),
    "target_yacl" to if (loader == "fabric") ">=$yaclVersion" else yaclVersion,
    "target_mru" to if (loader == "fabric") ">=$requiredModVersion" else requiredModVersion
)

tasks.named<ProcessResources>("processResources") {
    dependsOn("stonecutterGenerate")
    inputs.properties(resourceProperties)
    exclude(".cache/**")
    filesMatching(listOf("fabric.mod.json", "META-INF/neoforge.mods.toml")) {
        expand(resourceProperties)
    }
}

tasks.named<Jar>("sourcesJar") {
    dependsOn("stonecutterGenerate")
    exclude(".cache/**")
}

// Fabric datagen output for this Minecraft version, shared by both loaders
sourceSets.named("main") {
    resources.srcDir(moduleDirectory.dir("src/generated/$minecraftVersion"))
}

jsonlang {
    languageDirectories = listOf("assets/${metadata.id}/lang")
    prettyPrint = true
}

repositories {
    mavenCentral()
    maven("https://maven.architectury.dev")
    maven("https://maven.fabricmc.net/")
    maven("https://maven.neoforged.net/releases/")
    maven("https://maven.shedaniel.me/")
    maven {
        name = "Wisp Forest Maven"
        url = uri("https://maven.wispforest.io/releases/")
        content {
            includeGroupAndSubgroups("io.wispforest")
        }
    }
    maven("https://api.modrinth.com/maven") {
        content { includeGroup("maven.modrinth") }
    }
    maven {
        name = "Sleeping Town Maven"
        url = uri("https://repo.sleeping.town/")
        content {
            includeGroupAndSubgroups("folk.sisby")
            includeGroupAndSubgroups("dev.emi")
        }
    }
    maven {
        name = "Xander Maven"
        url = uri("https://maven.isxander.dev/releases")
        content {
            includeGroupAndSubgroups("dev.isxander")
            includeGroupAndSubgroups("org.quiltmc.parsers")
        }
    }
    maven {
        name = "Fuzs Mod Resources"
        url = uri("https://raw.githubusercontent.com/Fuzss/modresources/main/maven/")
        content {
            includeGroupAndSubgroups("fuzs")
        }
    }
    maven("https://maven.ladysnake.org/releases")
    maven {
        name = "Fabricators of Create (Snapshots)"
        url = uri("https://mvn.devos.one/snapshots")
        content {
            includeGroupAndSubgroups("net.createmod")
            includeGroupAndSubgroups("dev.engine-room")
            includeGroupAndSubgroups("io.github.fabricators_of_create")
            includeGroupAndSubgroups("com.simibubi")
        }
    }
    maven {
        name = "Fabricators of Create (Releases)"
        url = uri("https://mvn.devos.one/releases")
        content {
            includeGroupAndSubgroups("net.createmod")
            includeGroupAndSubgroups("dev.engine-room")
            includeGroupAndSubgroups("io.github.fabricators_of_create")
            includeGroupAndSubgroups("com.simibubi")
        }
    }
    maven("https://cursemaven.com") {
        content { includeGroup("curse.maven") }
    }
    maven {
        name = "Nucleoid Maven"
        url = uri("https://maven.nucleoid.xyz")
        content {
            includeGroupAndSubgroups("eu.pb4")
            includeGroupAndSubgroups("xyz.nucleoid")
        }
    }
    maven("https://maven.theillusivec4.top/")
    maven {
        name = "Sinytra"
        url = uri("https://maven.sinytra.org")
        content {
            includeGroupAndSubgroups("org.sinytra")
        }
    }
    maven("https://maven.terraformersmc.com/releases/")
    maven {
        name = "Cassian's Maven"
        url = uri("https://maven.cassian.cc")
        content {
            includeGroupAndSubgroups("cc.cassian")
            includeGroupAndSubgroups("folk.sisby")
        }
    }
    maven {
        name = "Gegy"
        url = uri("https://maven.gegy.dev/releases/")
        content {
            includeGroupAndSubgroups("dev.lambdaurora")
        }
    }
    maven {
        name = "Jared's maven"
        url = uri("https://maven.blamejared.com/")
        content {
            includeGroup("mezz.jei")
            includeGroup("net.mezzdev.config")
        }
    }
}
