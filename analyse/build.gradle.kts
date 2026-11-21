plugins {
    `java-library`
}

dependencies {
    // Analysis works on decoded instructions, so parse's types are part of this API.
    api(project(":parse"))

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
