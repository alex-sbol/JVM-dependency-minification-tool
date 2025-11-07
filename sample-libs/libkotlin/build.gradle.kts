plugins {
    kotlin("jvm") version "2.2.21"
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(kotlin("stdlib"))
}

tasks.jar {
    archiveBaseName.set("libkotlin")
}
