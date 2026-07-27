import org.jetbrains.kotlin.gradle.targets.web.nodejs.BaseNodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.web.yarn.BaseYarnRootEnvSpec

plugins {
    kotlin("multiplatform") version "2.4.10"
    kotlin("plugin.js-plain-objects") version "2.4.10"
}

group = "dev.ghostflyby"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    js {
        nodejs()
        useEsModules()
        generateTypeScriptDefinitions()
        binaries.executable()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
//                implementation("io.ktor:ktor-http:3.3.0")
                implementation("io.ktor:ktor-server-core-js:3.5.1")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
                implementation(kotlinWrappers.web)
            }
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xenable-suspend-function-exporting")
    }
}


extensions.configure<BaseNodeJsEnvSpec> {
    download = false
}

extensions.configure<BaseYarnRootEnvSpec> {
    download = false
}

tasks.configureEach {
    if (name.contains("npm", ignoreCase = true)
        || name.contains("yarn", ignoreCase = true)
        || name.contains("packageJson", ignoreCase = true)
    ) {
        enabled = false
    }
}
