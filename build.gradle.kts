plugins {
    kotlin("jvm") version "2.4.10"
    id("com.gradleup.shadow") version "9.6.0"
    id("maven-publish")
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
    maven("https://repo.glaremasters.me/repository/towny/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")
    compileOnly("com.palmergames.bukkit.towny:towny:0.103.0.7")
    implementation("org.xerial:sqlite-jdbc:3.53.2.0")
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation(kotlin("stdlib"))
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks {
    shadowJar {
        archiveClassifier.set("")
        relocate("or.xerial.sqlite-jdbc", "net.azisaba.townia.libs.sqlite")
        relocate("com.zaxxer.hikari", "net.azisaba.townia.libs.hikari")
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
    }

    processResources {
        val props = mapOf("version" to version, "description" to project.description)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}

publishing {
    repositories {
        maven {
            name = "repo"
            credentials(PasswordCredentials::class)
            url = uri(if (project.version.toString().endsWith("SNAPSHOT")) {
                project.findProperty("deploySnapshotURL")
                    ?: System.getProperty("deploySnapshotURL", "https://maven.azisaba.net/snapshots")
            } else {
                project.findProperty("deployReleasesURL")
                    ?: System.getProperty("deployReleasesURL", "https://maven.azisaba.net/releases")
            },
            )
        }
    }
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
}