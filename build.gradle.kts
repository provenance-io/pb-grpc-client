import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import java.net.URI

buildscript {
    repositories {
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.5.0")
    }
}


plugins {
    id("org.jetbrains.kotlin.jvm") version "1.3.72"
    `java-library`
    `maven-publish`
    signing
    id("io.github.gradle-nexus.publish-plugin") version "1.1.0"
}

repositories {
    mavenCentral()
    // For KEthereum library
    maven(url = "https://jitpack.io")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_1_8
    withSourcesJar()
    withJavadocJar()
}

val projectVersion = project.property("version")?.takeIf { it != "unspecified" } ?: "1.0-SNAPSHOT"

object Versions {
    val ProvenanceProtos = "1.7.0-0.0.2"
    val ProvenanceHDWallet = "0.1.9"
    val BouncyCastle = "1.63"
    val Kethereum = "0.83.4"
    val Komputing = "0.1"
    val Grpc = "1.42.0"
    val Protobuf = "3.19.2"
    val Kotlin = "1.5.0"
}

dependencies {

    // Kotlin
    // Pin kotlin packages together on a common version:
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:${Versions.Kotlin}"))
    implementation("org.jetbrains.kotlin:kotlin-allopen")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    // Provenance
    implementation("io.provenance.protobuf:pb-proto-java:${Versions.ProvenanceProtos}")
    implementation("io.provenance.hdwallet:hdwallet:${Versions.ProvenanceHDWallet}")

    // Grpc
    implementation("io.grpc:grpc-alts:${Versions.Grpc}")
    implementation("io.grpc:grpc-netty:${Versions.Grpc}")
    implementation("io.grpc:grpc-protobuf:${Versions.Grpc}")
    implementation("io.grpc:grpc-stub:${Versions.Grpc}")

    // Protobuf
    implementation("com.google.protobuf:protobuf-java-util:${Versions.Protobuf}")

    // Crypto
    implementation("org.bouncycastle:bcprov-jdk15on:${Versions.BouncyCastle}")
    implementation("com.github.komputing.kethereum:bip32:${Versions.Kethereum}")
    implementation("com.github.komputing.kethereum:bip39:${Versions.Kethereum}")
    implementation("com.github.komputing.kethereum:crypto:${Versions.Kethereum}")
    implementation("com.github.komputing.kethereum:crypto_api:${Versions.Kethereum}")
    implementation("com.github.komputing.kethereum:crypto_impl_bouncycastle:${Versions.Kethereum}")
    implementation("com.github.komputing.kethereum:extensions_kotlin:${Versions.Kethereum}")
    implementation("com.github.komputing.kethereum:model:${Versions.Kethereum}")
    implementation("com.github.komputing:kbase58:${Versions.Komputing}")
    implementation("com.github.komputing:kbip44:${Versions.Komputing}")

    // Test
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
}

configurations.forEach { it.exclude("org.slf4j", "slf4j-api") }


publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "io.provenance.client"
            artifactId = "pb-grpc-client-kotlin"
            version = "$projectVersion"

            from(components["java"])

            pom {
                name.set("Provenance Blockchain GRPC Kotlin Client")
                description.set("A GRPC client for communicating with the Provenance Blockchain")
                url.set("https://provenance.io")

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                developers {
                    developer {
                        id.set("vwagner")
                        name.set("Valerie Wagner")
                        email.set("tech@figure.com")
                    }
                }

                scm {
                    connection.set("git@github.com:provenance-io/pb-grpc-client-kotlin.git")
                    developerConnection.set("git@github.com/provenance-io/pb-grpc-client-kotlin.git")
                    url.set("https://github.com/provenance-io/pb-grpc-client-kotlin")
                }
            }
        }
    }
    signing {
        sign(publishing.publications["maven"])
    }
}


nexusPublishing {
    repositories {
        sonatype {
            nexusUrl.set(uri("https://s01.oss.sonatype.org/service/local/"))
            snapshotRepositoryUrl.set(uri("https://s01.oss.sonatype.org/content/repositories/snapshots/"))
            username.set(findProject("ossrhUsername")?.toString() ?: System.getenv("OSSRH_USERNAME"))
            password.set(findProject("ossrhPassword")?.toString() ?: System.getenv("OSSRH_PASSWORD"))
            stagingProfileId.set("3180ca260b82a7") // prevents querying for the staging profile id, performance optimization
        }
    }
}

object Repos {
    private object sonatype {
        const val snapshots = "https://s01.oss.sonatype.org/content/repositories/snapshots/"
        const val releases = "https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/"
    }

    fun RepositoryHandler.sonatypeOss(projectVersion: String): MavenArtifactRepository {
        val murl =
            if (projectVersion == "1.0-SNAPSHOT") sonatype.snapshots
            else sonatype.releases

        return maven {
            name = "Sonatype"
            url = URI.create(murl)
            credentials {
                username = System.getenv("OSSRH_USERNAME")
                password = System.getenv("OSSRH_PASSWORD")
            }
        }
    }
}


