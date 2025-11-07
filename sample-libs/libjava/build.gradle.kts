plugins {
    java
}

java {
    toolchain {
        languageVersion.set(org.gradle.jvm.toolchain.JavaLanguageVersion.of(17))
    }
}

tasks.jar {
    archiveBaseName.set("libjava")
}
