enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "cloudnet-kubernetes"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // CloudNet's own repository. It serves the transitive dependencies Central
        // does not carry: netty5 milestone builds and cloud-command-framework.
        maven("https://repository.derklaro.dev/releases/")
        maven("https://central.sonatype.com/repository/maven-snapshots/")
    }
    versionCatalogs {
        create("libs") {
            version("cloudnet", "4.0.0-RC17")
            version("fabric8", "7.8.0")
            version("junit", "5.11.4")
            version("shadow", "9.6.1")
            version("awssdk", "2.31.19")
            version("awaitility", "4.3.0")
            version("commonsCompress", "1.28.0")
            version("cyclonedx", "3.4.1")

            library("cloudnet-bom", "eu.cloudnetservice.cloudnet", "bom").versionRef("cloudnet")
            library("cloudnet-driver-api", "eu.cloudnetservice.cloudnet", "driver-api").withoutVersion()
            library("cloudnet-node-api", "eu.cloudnetservice.cloudnet", "node-api").withoutVersion()
            library("cloudnet-node-impl", "eu.cloudnetservice.cloudnet", "node-impl").withoutVersion()
            library("cloudnet-utils-base", "eu.cloudnetservice.cloudnet", "utils-base").withoutVersion()

            library("fabric8-bom", "io.fabric8", "kubernetes-client-bom").versionRef("fabric8")
            library("fabric8-client", "io.fabric8", "kubernetes-client").withoutVersion()
            library("fabric8-httpclient-jdk", "io.fabric8", "kubernetes-httpclient-jdk").withoutVersion()
            library("fabric8-mockserver", "io.fabric8", "kubernetes-server-mock").withoutVersion()

            library("awssdk-s3", "software.amazon.awssdk", "s3").versionRef("awssdk")
            library("commons-compress", "org.apache.commons", "commons-compress").versionRef("commonsCompress")

            library("junit-bom", "org.junit", "junit-bom").versionRef("junit")
            library("junit-jupiter", "org.junit.jupiter", "junit-jupiter").withoutVersion()
            library("awaitility", "org.awaitility", "awaitility").versionRef("awaitility")

            plugin("shadow", "com.gradleup.shadow").versionRef("shadow")
            plugin("cyclonedx", "org.cyclonedx.bom").versionRef("cyclonedx")
        }
    }
}

include(":k8s-runtime:api", ":k8s-runtime:impl")
