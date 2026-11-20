subprojects {
    group = "instructive"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }

    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion = JavaLanguageVersion.of(25)
            }
        }
    }
}
