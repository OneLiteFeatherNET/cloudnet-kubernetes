plugins {
    alias(libs.plugins.shadow)
}

dependencies {
    api(projects.k8sRuntime.api)

    // CloudNet is never bundled - the node provides it at runtime.
    compileOnlyApi(platform(libs.cloudnet.bom))
    compileOnlyApi(libs.cloudnet.node.impl)
    compileOnlyApi(libs.cloudnet.utils.base)

    implementation(platform(libs.fabric8.bom))
    implementation(libs.fabric8.client) {
        exclude(group = "io.fabric8", module = "kubernetes-httpclient-vertx")
        // CloudNet provides slf4j-api; bundling a second copy breaks logger binding.
        exclude(group = "org.slf4j")
    }
    // Compile scope, not runtimeOnly: we reference JdkHttpClientFactory directly to
    // bypass the ServiceLoader (see KubernetesClientFactory).
    implementation(libs.fabric8.httpclient.jdk) {
        exclude(group = "org.slf4j")
    }
    // The AWS SDK defaults to netty and apache http clients we do not need. Using the
    // JDK based url-connection-client keeps the shaded jar small and avoids dragging
    // a second netty generation in next to CloudNet's netty5.
    implementation(libs.awssdk.s3) {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "org.slf4j")
    }
    implementation(libs.awssdk.urlconnection) {
        exclude(group = "org.slf4j")
    }
    implementation(libs.commons.compress) {
        exclude(group = "org.slf4j")
    }

    testImplementation(platform(libs.fabric8.bom))
    testImplementation(libs.fabric8.mockserver)
    testImplementation(libs.fabric8.httpclient.jdk)
    testImplementation(libs.awaitility)
    testImplementation(platform(libs.cloudnet.bom))
    testImplementation(libs.cloudnet.node.impl)
}

tasks.shadowJar {
    archiveFileName = "cloudnet-kubernetes.jar"
    // Required: HttpClient$Factory and KubernetesResource service files.
    mergeServiceFiles()

    val shaded = "eu.cloudnetservice.modules.kubernetes.libs"
    relocate("com.fasterxml.jackson", "$shaded.jackson")
    relocate("org.snakeyaml", "$shaded.snakeyaml")
    relocate("org.bouncycastle", "$shaded.bouncycastle")
    relocate("io.fabric8", "$shaded.fabric8")
    // software.amazon, not software.amazon.awssdk: the eventstream package
    // sits beside it and would otherwise leak unrelocated.
    relocate("software.amazon", "$shaded.awssdk")
    relocate("reactor.blockhound", "$shaded.blockhound")
    relocate("org.apache.commons", "$shaded.commons")
    // org.yaml (snakeyaml) is a different artifact than org.snakeyaml
    // (snakeyaml-engine); both reach us and both must be moved.
    relocate("org.yaml", "$shaded.yaml")
    relocate("org.reactivestreams", "$shaded.reactivestreams")
    // org.slf4j is neither bundled nor relocated - CloudNet provides it, and a
    // second copy on the classpath breaks logger binding.
    exclude("org/slf4j/**")
    exclude("META-INF/services/org.slf4j.**")
}

publishing {
    publications.create<MavenPublication>("maven") { from(components["java"]) }
}

tasks.processResources {
    // runtimeModule: a registered service factory cannot be pulled out from under
    // running services, so the module must not be reloadable.
    // storesSensitiveData: kubeconfig and staging credentials.
    inputs.property("version", project.version)
    filesMatching("module.json") {
        expand("version" to project.version)
    }
}
