plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.dokka)
  alias(libs.plugins.dokka.javadoc)
  alias(libs.plugins.versions)
  alias(libs.plugins.nmcp)
  `maven-publish`
  signing
}

group = "io.liquidsoftware"
version = "0.2.0"

repositories {
    mavenCentral()
}

dependencies {
    // Kotlin Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlin.reflect)

    implementation(platform(libs.arrow.stack))
    implementation(libs.arrow.core)
    implementation(libs.arrow.fx.coroutines)
    implementation(libs.arrow.resilience)
    implementation(libs.jsoup)

    // Testing
    testImplementation(kotlin("test"))
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.framework.engine)
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

java {
    withSourcesJar()
}

tasks.register<Jar>("javadocJar") {
    val dokkaJavadoc = tasks.named("dokkaGenerate")
    dependsOn(dokkaJavadoc)
    archiveClassifier.set("javadoc")
    from(dokkaJavadoc.map { it.outputs.files })
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = "workflow"
            from(components["java"])
            artifact(tasks.named("javadocJar"))
            pom {
                name.set("workflow")
                description.set("A Kotlin workflow DSL for composing use cases.")
                url.set("https://github.com/edreyer/workflow")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        name.set("Erik Dreyer")
                    }
                }
                scm {
                    url.set("https://github.com/edreyer/workflow")
                    connection.set("scm:git:git://github.com/edreyer/workflow.git")
                    developerConnection.set("scm:git:ssh://github.com/edreyer/workflow.git")
                }
            }
        }
    }
}

signing {
    sign(publishing.publications["mavenJava"])
}

nmcp {
    publishAllPublications {
        username = providers.gradleProperty("centralPortalUsername")
        password = providers.gradleProperty("centralPortalPassword")
        publicationType = "AUTOMATIC"
    }
}
