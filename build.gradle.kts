subprojects {
    group = "instructive"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }

    // Everything compiles for Java 17, the oldest supported runtime, with whichever JDK
    // runs Gradle (17 or newer), so the same build tests on JDK 17, 21 and 25.
    tasks.withType<JavaCompile>().configureEach {
        options.release = 17
    }
}
