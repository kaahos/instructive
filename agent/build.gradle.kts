// The cpp-library and java plugins can't be applied together (both create an
// `implementation` configuration), so Java sources in src/main/java are built by a
// separately named source set ("jvm") on top of java-base.
plugins {
    `cpp-library`
    `java-base`
}

// JNI and JVMTI headers come from the JDK running Gradle.
val javaHome: String = System.getProperty("java.home")

val jvm = sourceSets.create("jvm") {
    java.setSrcDirs(listOf("src/main/java"))
    resources.setSrcDirs(listOf("src/main/resources"))
}

library {
    linkage = listOf(Linkage.SHARED)
    baseName = "instructive"
}

val macosSdkPath: Provider<String> = providers.exec {
    commandLine("xcrun", "--show-sdk-path")
}.standardOutput.asText.map(String::trim)

tasks.withType<CppCompile>().configureEach {
    val os = org.gradle.nativeplatform.platform.internal.DefaultNativePlatform.getCurrentOperatingSystem()
    val platformInclude = when {
        os.isMacOsX -> "darwin"
        os.isLinux -> "linux"
        os.isWindows -> "win32"
        else -> error("Unsupported OS: ${os.name}")
    }
    includes.from("$javaHome/include", "$javaHome/include/$platformInclude")
    compilerArgs.add("-std=c++17")
    // Export only what JNIEXPORT marks (the JNI entry points), not our internals or the
    // templates instantiated in them.
    compilerArgs.addAll("-fvisibility=hidden", "-fvisibility-inlines-hidden")
    if (os.isMacOsX) {
        // Gradle looks for libc++ headers in CommandLineTools/usr/include/c++/v1, which newer
        // Command Line Tools no longer ship; they live in the SDK. compilerArgs precede
        // Gradle's own -isystem entries, so libc++ is found ahead of the C headers.
        compilerArgs.addAll(macosSdkPath.map { listOf("-isystem", "$it/usr/include/c++/v1") })
    }
}

val exportsMap = file("src/main/linker/exports.map")

tasks.withType<LinkSharedLibrary>().configureEach {
    if (org.gradle.nativeplatform.platform.internal.DefaultNativePlatform.getCurrentOperatingSystem().isLinux) {
        linkerArgs.addAll(
            // timer_create lives in librt on glibc older than 2.34.
            "-lrt",
            // Bundle the C++ runtime so the library loads on distros with an older one,
            // and keep its symbols out of the dynamic symbol table so they can't clash
            // with another copy loaded in the same process.
            "-static-libstdc++",
            "-static-libgcc",
            "-Wl,--exclude-libs,ALL",
            // Export only the JNI entry points; see the map for why visibility isn't enough.
            "-Wl,--version-script=${exportsMap.absolutePath}",
        )
        inputs.file(exportsMap)
    }
}

// These must agree with NativeLibraryLoader.platform() on the Java side.
// musl-based Linux (e.g. Alpine) needs its own build: a library linked against glibc
// can't load there. It is recognised by musl's dynamic loader.
fun isMusl(): Boolean = File("/lib").listFiles()?.any { it.name.startsWith("ld-musl-") } ?: false

fun normaliseOperatingSystem(family: OperatingSystemFamily): String = when {
    family.isLinux -> if (isMusl()) "linux-musl" else "linux"
    family.isMacOs -> "macos"
    family.isWindows -> "windows"
    else -> error("Unsupported operating system: ${family.name}")
}

fun normaliseArchitecture(name: String): String = when (name.lowercase()) {
    "x86-64", "x86_64", "amd64" -> "x86_64"
    "aarch64", "arm64", "arm-v8" -> "aarch64"
    else -> error("Unsupported architecture: $name")
}

// The host's native library laid out as META-INF/native-libs/<os>-<arch>/<lib>. In a
// multi-platform build each matrix job publishes this directory.
val nativeLibs = tasks.register<Sync>("nativeLibs") {
    into(layout.buildDirectory.dir("native-libs"))
}

// Native binaries are only created after evaluation.
library.binaries.whenElementFinalized(CppSharedLibrary::class.java) {
    if (isOptimized) {
        val platform = normaliseOperatingSystem(targetMachine.operatingSystemFamily) +
            "-" + normaliseArchitecture(targetMachine.architecture.name)
        val binary = runtimeFile
        nativeLibs.configure {
            from(binary) {
                into("META-INF/native-libs/$platform")
            }
        }
    }
}

// Native libraries built elsewhere (e.g. by other matrix jobs), each a directory
// containing META-INF/native-libs/<os>-<arch>/...; comma separated.
val externalNativeLibs: List<String> = providers.gradleProperty("instructive.nativeLibs")
    .map { value -> value.split(',').map(String::trim).filter(String::isNotEmpty) }
    .getOrElse(emptyList())

// Whether to build the host's native library and put it in the jar. CI packages the
// jar from libraries built and tested on each platform instead, with
// -Pinstructive.buildNative=false -Pinstructive.nativeLibs=<dir>.
val buildNative: Boolean = providers.gradleProperty("instructive.buildNative").map(String::toBoolean).getOrElse(true)

val jvmJar = tasks.register<Jar>("jvmJar") {
    archiveBaseName = "agent"
    from(jvm.output)
    // The locally built library wins over an external one for the same platform.
    if (buildNative) {
        from(nativeLibs)
    }
    externalNativeLibs.forEach { from(it) }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Premain-Class" to "instructive.agent.InstructiveAgent",
            "Agent-Class" to "instructive.agent.InstructiveAgent",
        )
    }
}

tasks.named("assemble") {
    dependsOn(jvmJar)
}

// Native unit tests: each src/test/cpp/<name>_test.cpp is compiled with the source it
// tests, src/main/cpp/<name>.cpp, into an executable with the host's C++ compiler, and
// run; a non-zero exit fails the build. -Pinstructive.sanitize=thread (or
// address,undefined) builds them with a sanitizer, where the toolchain supports it.
val sanitize: String? = providers.gradleProperty("instructive.sanitize").orNull
val nativeTest = tasks.register("nativeTest") {
    group = "verification"
    description = "Builds and runs the native unit tests."
}
fileTree("src/test/cpp") { include("*_test.cpp") }.files.sorted().forEach { test ->
    val name = test.name.removeSuffix("_test.cpp")
    val taskName = name.split('_').joinToString("") { it.replaceFirstChar(Char::uppercase) }
    val source = file("src/main/cpp/$name.cpp")
    val executable = layout.buildDirectory.file("native-test/${name}_test")
    val compile = tasks.register<Exec>("compile${taskName}Test") {
        inputs.files(test, source, fileTree("src/main/headers"))
        inputs.property("sanitize", sanitize ?: "")
        outputs.file(executable)
        doFirst { executable.get().asFile.parentFile.mkdirs() }
        val os = org.gradle.nativeplatform.platform.internal.DefaultNativePlatform.getCurrentOperatingSystem()
        val arguments = mutableListOf("c++", "-std=c++17", "-Wall", "-Wextra", "-pthread", "-Isrc/main/headers")
        if (os.isMacOsX) {
            arguments.addAll(listOf("-isystem", macosSdkPath.get() + "/usr/include/c++/v1"))
        }
        arguments.addAll(if (sanitize != null) listOf("-O1", "-g", "-fsanitize=$sanitize") else listOf("-O2"))
        arguments.addAll(listOf(test.path, source.path, "-o", executable.get().asFile.path))
        commandLine(arguments)
    }
    val run = tasks.register<Exec>("run${taskName}Test") {
        dependsOn(compile)
        inputs.file(executable)
        commandLine(executable.get().asFile.path)
    }
    nativeTest.configure { dependsOn(run) }
}
tasks.named("check") {
    dependsOn(nativeTest)
}

// Consumable output for other projects (e.g. agent-test).
val agentJar = configurations.create("agentJar") {
    isCanBeConsumed = true
    isCanBeResolved = false
}

// The Java side uses the parse module's disassembler (InstructionProfileFormatter).
// Declaring it on agentJar too passes it on to projects that consume the agent jar.
dependencies {
    "jvmImplementation"(project(":parse"))
    agentJar(project(":parse"))
}

artifacts {
    add(agentJar.name, jvmJar)
}
