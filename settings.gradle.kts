import org.gradle.kotlin.dsl.maven

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "gitea"

dependencyResolutionManagement {
    repositories {
        maven {
            url = uri("https://nexus.funkemunky.cc/repository/maven-central/")
        }
        mavenCentral()
    }
}

includeBuild("tea4j-autodeploy")
