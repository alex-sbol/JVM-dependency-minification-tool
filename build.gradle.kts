plugins {
    kotlin("jvm") version "2.2.21"
    id("com.gradleup.shadow") version "9.0.0"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.ow2.asm:asm:9.7")
    implementation("org.ow2.asm:asm-tree:9.7")
    implementation("org.ow2.asm:asm-commons:9.7")

    // Kotlin metadata
    implementation("org.jetbrains.kotlinx:kotlinx-metadata-jvm:0.9.0")

    // CLI
    implementation("com.github.ajalt.clikt:clikt:4.4.0")

    // Logging
    implementation("org.slf4j:slf4j-simple:2.0.16")
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("tool.MinifyKt")
}

tasks.shadowJar {
    archiveClassifier.set("all")
    mergeServiceFiles()
}
