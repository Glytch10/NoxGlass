plugins {
    id("imb11.fabric-remap-mod")
}

loom {
    accessWidenerPath = project.parent!!.file("src/main/resources/glass.accesswidener")
}

dependencies {
    "modCompileOnly"(mcLibrary("sodium-fabric"))
    "modCompileOnly"(mcLibrary("iris-fabric"))
    if (providers.gradleProperty("with_sodium").isPresent) {
        "modLocalRuntime"(mcLibrary("sodium-fabric"))
    }
}
