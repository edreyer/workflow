import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.dokka)
  alias(libs.plugins.dokka.javadoc)
  alias(libs.plugins.versions)
  alias(libs.plugins.nmcp)
  `maven-publish`
  signing
}

group = "io.liquidsoftware"
version = "0.10.0"

repositories {
  mavenCentral()
}

dependencies {
  // Kotlin Coroutines
  implementation(libs.kotlinx.coroutines.core)

  // Either and Raise are part of the public API, so consumers need Arrow on their compile classpath.
  api(platform(libs.arrow.stack))
  api(libs.arrow.core)
  implementation(libs.slf4j.api)

  // Testing
  testImplementation(kotlin("test"))
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.logback.classic)
}

tasks.test {
  useJUnitPlatform()
  // CI passes -PtestJdk=17 to prove the published bytecode runs on those JDKs.
  providers.gradleProperty("testJdk").orNull?.let { jdk ->
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(jdk.toInt())) })
  }
}

// Build with JDK 25, but publish bytecode and Gradle metadata that consumers on Java 17+ can use.
val consumerJvmTarget = 17

kotlin {
  jvmToolchain(25)
  explicitApi()
  @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
  abiValidation()
  compilerOptions {
    jvmTarget.set(JvmTarget.fromTarget(consumerJvmTarget.toString()))
    freeCompilerArgs.add("-Xjdk-release=$consumerJvmTarget")
  }
}

java {
  withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
  options.release.set(consumerJvmTarget)
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
  publishAllPublicationsToCentralPortal {
    username.set(providers.gradleProperty("centralPortalUsername"))
    password.set(providers.gradleProperty("centralPortalPassword"))
    publishingType.set("AUTOMATIC")
  }
}
