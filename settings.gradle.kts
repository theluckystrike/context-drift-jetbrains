plugins {
    // Lets Gradle download the JDK 21 toolchain that the IntelliJ Platform 2025.2 build needs.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "context-drift-jetbrains"
