plugins {
    kotlin("jvm") version "2.4.21"
    id("com.gradleup.shadow") version "9.6.1"
    id("maven-publish")
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
    maven("https://repo.glaremasters.me/repository/towny/")
    maven("https://repo.azisaba.net/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")
    compileOnly("com.palmergames.bukkit.towny:towny:0.103.2.15")
    compileOnly("de.epiceric:ShopChest:2.0.5+1.21.11") { isTransitive = false }
    implementation("org.xerial:sqlite-jdbc:3.54.1.0")
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation(kotlin("stdlib"))
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation("com.github.MilkBowl:VaultAPI:1.7.1")
    testImplementation("de.epiceric:ShopChest:2.0.5+1.21.11") { isTransitive = false }
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks {
    test {
        useJUnitPlatform()
    }

    shadowJar {
        archiveClassifier.set("")
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        relocate("or.xerial.sqlite-jdbc", "net.azisaba.townia.libs.sqlite")
        relocate("com.zaxxer.hikari", "net.azisaba.townia.libs.hikari")
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
    }

    processResources {
        val props = mapOf("version" to project.version.toString(), "description" to project.description.orEmpty())
        // expand() values are not tracked automatically; without these, a version bump
        // leaves processResources UP-TO-DATE / cache-hit with the old plugin.yml.
        inputs.properties(props)
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
            url = uri(
                if (project.version.toString().endsWith("-SNAPSHOT"))
                    "https://maven.azisaba.net/snapshots"
                else
                    "https://maven.azisaba.net/releases"
            )
        }
    }

    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
}