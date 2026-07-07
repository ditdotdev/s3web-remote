// Copyright Dit 2026
// SPDX-License-Identifier: BUSL-1.1

plugins {
    kotlin("jvm")
    jacoco
    "com.github.ben-manes.versions"
    `maven-publish`

}

repositories {
    mavenCentral()
    maven("https://dl.bintray.com/kotlin/kotlinx")
    maven {
        name = "dit"
        url = uri("https://dit-maven.s3.amazonaws.com")
    }
}

dependencies {
	implementation(kotlin("stdlib"))
	implementation("dev.dit:remote-sdk:1.9.8")
	implementation("com.google.code.gson:gson:2.14.0")
	implementation("com.squareup.okhttp3:okhttp:5.4.0")
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
val mavenBucket = when(project.hasProperty("mavenBucket")) {
    true -> project.property("mavenBucket")
    false -> "dit-maven"
}

publishing {
	publications {
		create<MavenPublication>("maven") {
			groupId = "dev.dit"
			artifactId = "s3web-remote-server"

			from(components["java"])
		}
	}

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

