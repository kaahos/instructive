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

// Every test JVM runs with the agent, and the compile commands the tests rely on.
tasks.withType<Test>().configureEach {
    // The agent's native unit tests run wherever its integration tests do, CI included.
    dependsOn(":agent:nativeTest")
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
            "-XX:CompileCommand=dontinline,instructive.agenttest.PointerChasingTest::sum*",
            "-XX:CompileCommand=dontinline,instructive.agenttest.FailedCheckTest::sum*",
            "-XX:CompileCommand=dontinline,instructive.agenttest.UnpredictableBranchTest::dispatch*",
            "-XX:CompileCommand=dontinline,instructive.agenttest.UnpredictableBranchTest::chain*",
            "-XX:CompileCommand=dontinline,instructive.agenttest.UnpredictableBranchTest::select",
        )
    })
}

// Tests tagged "small-counter-storage" need a JVM of their own, whose counter storage
// is too small for every sampled method.
val smallCounterStorageTest = tasks.register<Test>("smallCounterStorageTest") {
    description = "Runs the tests that need the per-PC counter storage to fill up."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("small-counter-storage")
    }
    systemProperty("instructive.counterCapacity", "1024")
}

tasks.test {
    useJUnitPlatform {
        excludeTags("small-counter-storage")
    }
    // So that running the tests (as CI does) runs these too.
    dependsOn(smallCounterStorageTest)
}
