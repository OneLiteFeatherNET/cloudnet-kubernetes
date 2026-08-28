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
    }
    // Compile scope, not runtimeOnly: we reference JdkHttpClientFactory directly to
    // bypass the ServiceLoader (see KubernetesClientFactory).
    implementation(libs.fabric8.httpclient.jdk)
    implementation(libs.awssdk.s3)
    implementation(libs.commons.compress)

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
    relocate("software.amazon.awssdk", "$shaded.awssdk")
    relocate("org.apache.commons.compress", "$shaded.compress")
    // org.slf4j stays unrelocated so log output lands in CloudNet's logging.
}

publishing {
    publications.create<MavenPublication>("maven") { from(components["java"]) }
}
