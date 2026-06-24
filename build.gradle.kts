plugins {
    kotlin("jvm") version "2.4.0"
    id("com.gradleup.shadow") version "9.4.2"
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
    compileOnly("com.palmergames.bukkit.towny:towny:0.100.3.0")
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
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
