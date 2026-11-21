plugins {
    java
}

// Just the agent jar, for -javaagent; its dependencies come through testImplementation.
val agentJar = configurations.create("agentJar") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    agentJar(project(path = ":agent", configuration = "agentJar"))
    testImplementation(project(path = ":agent", configuration = "agentJar"))
    testImplementation(project(":parse"))
    testImplementation(project(":analyse"))

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
            // Arrays.hashCode(int[]) as a scalar loop rather than the SIMD stub, for
            // DependencyChainTest. The flag doesn't exist on every JDK.
            "-XX:+IgnoreUnrecognizedVMOptions",
            "-XX:+UnlockDiagnosticVMOptions",
            "-XX:-UseVectorizedHashCodeIntrinsic",
            "-XX:CompileCommand=exclude,instructive.agenttest.CodeCacheTest::interpretedLoop",
            // DependencyChainTest profiles these as methods of their own.
            "-XX:CompileCommand=dontinline,instructive.agenttest.DependencyChainTest::encodeDeltas",
            "-XX:CompileCommand=dontinline,instructive.agenttest.DependencyChainTest::decodeDeltas",
            // NotUnrolledLoopTest: limit() stays a call in the loop's condition, and each
            // loop is profiled as a method of its own.
            "-XX:CompileCommand=dontinline,instructive.agenttest.NotUnrolledLoopTest::*",
        )
    })
}
