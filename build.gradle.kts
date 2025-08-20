plugins {
  kotlin("jvm") version "2.2.10"
  kotlin("plugin.serialization") version "2.2.10"
  id("com.github.ben-manes.versions") version "0.52.0"
}

group = "com.liquidsoftware"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val arrowVersion = "2.1.2"

dependencies {
    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.2.10")

    implementation(platform("io.arrow-kt:arrow-stack:$arrowVersion"))
    implementation("io.arrow-kt:arrow-core")
    implementation("io.arrow-kt:arrow-fx-coroutines")
    implementation("io.arrow-kt:arrow-resilience")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("io.kotest:kotest-runner-junit5:5.8.0")
    testImplementation("io.kotest:kotest-assertions-core:5.8.0")
    testImplementation("io.kotest:kotest-framework-engine:5.8.0")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(23)
}

