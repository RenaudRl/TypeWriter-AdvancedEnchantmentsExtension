

plugins {
    kotlin("jvm") version "2.0.21"
    id("com.typewritermc.module-plugin") version "1.3.0"
}

group = "btc.renaud"
version = "0.0.1"

repositories {
    mavenCentral()
    maven("https://maven.typewritermc.com/beta/")
    maven("https://repo.hibiscusmc.com/releases")
}

dependencies {
    implementation("com.typewritermc:EntityExtension:0.9.0")
    implementation("com.hibiscusmc:HMCCosmetics:2.8.0-13303096")
    // Add paper api as compile-only so Bukkit/Paper types (e.g. org.bukkit.entity.Player) are available at compile-time
    compileOnly("io.papermc.paper:paper-api:1.20.2-R0.1-SNAPSHOT")
}

typewriter {
    namespace = "renaud"

    extension {
        name = "HmcCosmetic"
        shortDescription = "Typewriter extension for HmcCosmetic support."
        description =
            "This extension adds support for HmcCosmetic in Typewriter, allowing you to configure data equipment with cosmetics."
        engineVersion = "0.9.0-beta-163"
        channel = com.typewritermc.moduleplugin.ReleaseChannel.BETA
        dependencies {
            dependency("typewritermc", "Entity")
        }
        paper {
            dependency("HmcCosmetics")
        }

    }

}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
