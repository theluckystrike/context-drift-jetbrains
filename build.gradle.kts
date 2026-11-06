import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.0"
}

group = "zovo.contextdrift"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion"))
        bundledPlugin("org.intellij.plugins.markdown")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

kotlin { jvmToolchain(21) }

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
tasks.withType<JavaCompile> { options.release.set(21) }

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        // IntelliJ 2025.2 ships the Kotlin 2.1+ standard library: do not call newer stdlib APIs.
        apiVersion.set(KotlinVersion.KOTLIN_2_1)
        languageVersion.set(KotlinVersion.KOTLIN_2_1)
        freeCompilerArgs.add("-Xjdk-release=21")
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "zovo.context-drift"
        name = "Context Drift: AGENTS.md & CLAUDE.md Linter"
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
        vendor {
            name = "Zovo"
            url = "https://github.com/theluckystrike/context-drift"
        }
    }
    pluginVerification {
        ides {
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2025.2.6.4")
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdea, "2026.3")
        }
    }
    publishing {
        // Owner step: export PUBLISH_TOKEN=<JetBrains Marketplace permanent token>, then ./gradlew publishPlugin
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    buildSearchableOptions = false
}

tasks {
    // ./gradlew runIde -PdemoProject=<folder> -PdemoOut=<folder>: opens the folder, takes the listing screenshots, exits.
    runIde {
        val demo = providers.gradleProperty("demoProject").orNull
        val shots = providers.gradleProperty("demoOut").orNull
        if (demo != null) args(listOf(demo))
        if (shots != null) jvmArgs("-Dcontextdrift.demo.capture=$shots", "-Dcontextdrift.demo.exit=true")
        // skip first-run dialogs (data sharing consent, settings import, trust) so the sandbox opens unattended
        jvmArgs(
            "-Didea.trust.all.projects=true", "-Dide.show.tips.on.startup.default.value=false",
            "-Djb.consents.confirmation.enabled=false", "-Djb.privacy.policy.text=<!--999.999-->",
            "-Didea.initially.ask.config=never", "-Didea.suppress.statistics.report=true", "-Xmx2g",
        )
    }
    test {
        // the engine tests write small temp repos; keep them out of /tmp clean-up races
        systemProperty("contextdrift.testRoot", layout.buildDirectory.dir("test-repos").get().asFile.absolutePath)
    }
}
