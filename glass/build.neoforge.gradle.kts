plugins {
    id("imb11.neoforge-mod")
}

neoForge {
    accessTransformers.from(project.parent!!.file("src/main/resources/META-INF/accesstransformer.cfg"))
}

val localRuntime = configurations.create("localRuntime")
configurations.named("runtimeClasspath") {
    extendsFrom(localRuntime)
}

val sodiumDistribution = configurations.create("sodiumDistribution") {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    add(sodiumDistribution.name, mcLibrary("sodium-neoforge"))
    compileOnly(mcLibrary("iris-neoforge"))
    compileOnly(mcLibrary("sable-companion"))
    listOf("sable-neoforge", "create-neoforge", "create-aeronautics-neoforge").forEach { mod ->
        compileOnly(mcLibrary(mod))
        add(localRuntime.name, mcLibrary(mod))
    }
    listOf("create-connected-neoforge", "create-bits-n-bobs-neoforge", "azimuth-api-neoforge", "strut-your-stuff-neoforge").forEach { mod ->
        add(localRuntime.name, mcLibrary(mod))
    }
    compileOnly(files(provider {
        sodiumDistribution.files.flatMap { zipTree(it).matching { include("META-INF/jarjar/net.caffeinemc.sodium-*-mod.jar") }.files }
    }))
    if (providers.gradleProperty("with_sodium").isPresent) {
        runtimeOnly(mcLibrary("sodium-neoforge"))
    }
}
