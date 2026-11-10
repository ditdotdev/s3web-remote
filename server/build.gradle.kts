// Copyright Dit 2026
// SPDX-License-Identifier: BUSL-1.1

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar

plugins {
    kotlin("jvm")
    jacoco
    "com.github.ben-manes.versions"
    id("com.vanniktech.maven.publish") version("0.37.0")

}

repositories {
    mavenCentral()
    maven("https://dl.bintray.com/kotlin/kotlinx")
    maven {
        name = "dit"
        url = uri("https://maven.dit.dev")
    }
}

dependencies {
	implementation(kotlin("stdlib"))
	implementation("dev.dit:remote-sdk:1.10.4")
	implementation("com.google.code.gson:gson:2.15.0")
	implementation("com.squareup.okhttp3:okhttp:5.5.1")
	testImplementation("io.kotlintest:kotlintest-runner-junit5:3.4.2")
	testImplementation("io.mockk:mockk:1.14.11")
}

// Jar configuration
group = "dev.dit"
version = when(project.hasProperty("version")) {
    true -> project.property("version")!!
    false -> "latest"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

val jar by tasks.getting(Jar::class) {
    archiveBaseName.set("s3web-remote")
}

// Maven publishing configuration
//
// Releases are published to Maven Central (namespace dev.dit) by the
// com.vanniktech.maven.publish plugin, which also supplies the sources jar,
// javadoc jar and signatures that Central requires. Credentials and the
// signing key come from Gradle properties (ORG_GRADLE_PROJECT_* in CI); see
// DEVELOPING.md.
mavenPublishing {
    // Upload as a deployment that waits for a manual "Publish" click in the
    // Central Portal: anything released to Central is permanent.
    publishToMavenCentral()

    // Sign only when a key is supplied, so `publishToMavenLocal` works on a
    // developer machine or pull request build with no key. A release without
    // signatures cannot slip through: Central rejects unsigned deployments.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    // Kotlin sources have no javadoc; Central accepts an empty javadoc jar.
    configureBasedOnAppliedPlugins(
        javadocJar = JavadocJar.Empty(),
        sourcesJar = SourcesJar.Sources(),
    )

    coordinates("dev.dit", "s3web-remote-server", version.toString())

    pom {
        name.set("Dit S3 Web Remote Provider")
        description.set("Read-only Dit remote provider for commits published over HTTP by the S3 provider.")
        inceptionYear.set("2026")
        url.set("https://github.com/ditdotdev/s3web-remote")
        licenses {
            license {
                name.set("BUSL-1.1")
                url.set("https://github.com/ditdotdev/s3web-remote/blob/master/LICENSE")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("ditdotdev")
                name.set("Dit")
                url.set("https://github.com/ditdotdev")
            }
        }
        scm {
            url.set("https://github.com/ditdotdev/s3web-remote")
            connection.set("scm:git:https://github.com/ditdotdev/s3web-remote.git")
            developerConnection.set("scm:git:ssh://git@github.com/ditdotdev/s3web-remote.git")
        }
    }
}

// The dit-maven S3 bucket (served as https://maven.dit.dev) stays as a second
// publish target while the ecosystem migrates to Maven Central: the release
// tooling still verifies artifacts there. Remove it once every dev.dit
// artifact is released from Central.
val mavenBucket = when (project.hasProperty("mavenBucket")) {
    true -> project.property("mavenBucket")
    false -> "dit-maven"
}

publishing {
    repositories {
        maven {
            name = "dit"
            url = uri("s3://$mavenBucket")
            authentication {
                create<AwsImAuthentication>("awsIm")
            }
        }
    }
}

// Test configuration

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.named("jacocoTestReport"))
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.test)
    reports {
        csv.required.set(true)
        xml.required.set(true)
        html.required.set(true)
    }
}

