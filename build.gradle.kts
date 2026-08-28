plugins {
    alias(libs.plugins.shadow) apply false
    // Applied at the root so `cyclonedxBom` produces one aggregate SBOM for the
    // DependencyTrack upload in the release workflow.
    alias(libs.plugins.cyclonedx)
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    group = "dev.onelitefeather.cloudnet"
    version = "0.1.0" // x-release-please-version

    extensions.configure<JavaPluginExtension> {
        // The api module stays on 17 so it can be consumed broadly; impl compiles
        // against CloudNet's node-impl, which targets 25.
        toolchain.languageVersion = JavaLanguageVersion.of(if (project.name == "api") 17 else 25)
        withSourcesJar()
    }

    dependencies {
        "testImplementation"(platform(rootProject.libs.junit.bom))
        "testImplementation"(rootProject.libs.junit.jupiter)
        // Gradle 9 no longer puts the launcher on the test runtime classpath itself.
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging { events("passed", "skipped", "failed") }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-Xlint:-processing")
    }

    extensions.configure<PublishingExtension> {
        repositories {
            maven {
                name = "OneLiteFeatherRepository"
                url = uri("https://repo.onelitefeather.dev/onelitefeather")
                credentials {
                    username = System.getenv("ONELITEFEATHER_MAVEN_USERNAME")
                    password = System.getenv("ONELITEFEATHER_MAVEN_PASSWORD")
                }
            }
        }
    }
}
