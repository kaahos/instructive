// The cpp-library and java plugins can't be applied together (both create an
// `implementation` configuration), so Java sources in src/main/java are built by a
// separately named source set ("jvm") on top of java-base.
plugins {
    `cpp-library`
    `java-base`
}

val javaHome: String = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(25)
}.get().metadata.installationPath.asFile.absolutePath

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

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
    if (os.isMacOsX) {
        // Gradle looks for libc++ headers in CommandLineTools/usr/include/c++/v1, which newer
        // Command Line Tools no longer ship; they live in the SDK. compilerArgs precede
        // Gradle's own -isystem entries, so libc++ is found ahead of the C headers.
        compilerArgs.addAll(macosSdkPath.map { listOf("-isystem", "$it/usr/include/c++/v1") })
    }
}

// timer_create lives in librt on glibc older than 2.34.
tasks.withType<LinkSharedLibrary>().configureEach {
    if (org.gradle.nativeplatform.platform.internal.DefaultNativePlatform.getCurrentOperatingSystem().isLinux) {
        linkerArgs.add("-lrt")
    }
}

// These must agree with NativeLibraryLoader.platform() on the Java side.
fun normaliseOperatingSystem(family: OperatingSystemFamily): String = when {
    family.isLinux -> "linux"
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

val jvmJar = tasks.register<Jar>("jvmJar") {
    archiveBaseName = "agent"
    from(jvm.output)
    // The locally built library wins over an external one for the same platform.
    from(nativeLibs)
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

// Consumable output for other projects (e.g. agent-test).
val agentJar = configurations.create("agentJar") {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add(agentJar.name, jvmJar)
}
