plugins {
    java
}

val agentJar = configurations.create("agentJar") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    agentJar(project(path = ":agent", configuration = "agentJar"))
    testImplementation(project(path = ":agent", configuration = "agentJar"))
    testImplementation(project(":parse"))

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    inputs.files(agentJar)
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "-javaagent:${agentJar.singleFile.absolutePath}",
            "--enable-native-access=ALL-UNNAMED",
            "-XX:CompileCommand=quiet",
            "-XX:CompileCommand=exclude,instructive.agenttest.CodeCacheTest::interpretedLoop",
        )
    })
}
