plugins {
    id("imb11.fabric-mod")
}

loom {
    accessWidenerPath = project.parent!!.file("src/main/resources/glass.accesswidener")
}

dependencies {
    compileOnly(mcLibrary("sodium-fabric"))
    compileOnly(mcLibrary("iris-fabric"))
    if (providers.gradleProperty("with_sodium").isPresent) {
        "localRuntime"(mcLibrary("sodium-fabric"))
    }
}
