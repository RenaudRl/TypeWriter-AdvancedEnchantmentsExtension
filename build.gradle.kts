

plugins {
    kotlin("jvm") version "2.0.21"
    id("com.typewritermc.module-plugin") version "1.3.0"
}

group = "btc.renaud.enchantextension"
version = "0.9.0" // The version is the same with the plugin to avoid confusion. :)

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    flatDir { // This is a temporary solution, because the MythicDungeons maven repo doesn't have the v2 api yet.
        dirs("libs")
    }
}

dependencies {
    // compileOnly(":AdvancedEnchantmentsAPI") // Uncomment when AdvancedEnchantments API is available
    implementation("com.typewritermc:QuestExtension:0.9.0")
    implementation("com.typewritermc:BasicExtension:0.9.0")
    compileOnly(":AdvancedEnchantmentsAPI") // Assuming AdvancedEnchantments is a local module, adjust if it's a dependency from a repository
}

typewriter {
    namespace = "renaud"

    extension {
        name = "AdvancedEnchantments"
        shortDescription = "Typewriter extension for AdvancedEnchantments support."
        description =
            "This extension adds support for AdvancedEnchantments enchantments in Typewriter, allowing you to configure triggers for enchantment application in both enchanting tables and anvils."
        engineVersion = "0.9.0-beta-162"
        channel = com.typewritermc.moduleplugin.ReleaseChannel.BETA
        dependencies {
            dependency("typewritermc", "Quest")
        }
        paper {
            dependency("AdvancedEnchantments")
        }

    }

}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
