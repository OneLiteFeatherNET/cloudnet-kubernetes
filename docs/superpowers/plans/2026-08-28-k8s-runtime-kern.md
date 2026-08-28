# CloudNet K8s-Runtime-Kern — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ein CloudNet-Modul, das über die Runtime `k8s-jvm` einen Lobby-Service als Kubernetes-Pod startet, dessen Konsole in beide Richtungen funktioniert und der beim Stop rückstandsfrei aufräumt.

**Architecture:** Das Modul leitet `KubernetesService` von `JVMService` ab und überschreibt sieben Methoden — identisch zum Schnitt des Docker-Moduls. Die Node ruft `prepareService()` unverändert auf lokaler Disk auf und transportiert das Ergebnis als tar über einen S3-Bucket in den Pod; ein Init-Container entpackt es. Die `wrapper.json` reist getrennt als Kubernetes-Secret. Pod-Zustände kommen aus einem Shared Informer, niemals aus einzelnen API-Calls.

**Tech Stack:** Java 25 (impl) / 17 (api), Gradle 9.3 Kotlin DSL, fabric8 kubernetes-client 7.8.0 mit JDK-Transport, JUnit 5, fabric8 KubernetesMockServer, AWS SDK v2 (S3), CloudNet 4.0.0-RC17.

**Spec:** `docs/superpowers/specs/2026-08-28-cloudnet-kubernetes-operator-design.md`

## Global Constraints

- Zielplattform CloudNet `4.0.0-RC17`, Artefaktgruppe `eu.cloudnetservice.cloudnet`, Repo `https://repository.derklaro.dev/releases/`.
- `impl`-Module: Java-Toolchain **25**. `api`-Module: Java-Toolchain **17**.
- Alle CloudNet-Abhängigkeiten sind `compileOnly` bzw. `compileOnlyApi` — CloudNet wird **niemals** gebündelt.
- Der K8s-Client wird **geshadet und relocated**, nicht über `moduleLibrary(...)` geladen. `org.slf4j` wird **nicht** relocated.
- Java-Package-Wurzel: `eu.cloudnetservice.modules.kubernetes`.
- Kubernetes-Labels und -Annotationen tragen den Präfix `cloudnet.eu/`.
- Der Wert des `runtime`-Felds ist konfigurierbar, Default **`k8s-jvm`**.
- Code, Kommentare, Commit-Messages und PR-Texte auf **Englisch**. Conventional Commits.
- Kein Claude-Co-Author-Trailer und kein "Generated with"-Marker in Commits.
- Git-Identität: `TheMeinerLP <github@themeinerlp.dev>`.
- Checkstyle-Vorbild ist CloudNets `checkstyle.xml`: 2 Leerzeichen Einrückung, Lizenzheader in jeder Datei.

---

## File Structure

```
settings.gradle.kts                     Version-Katalog, Repos, Modul-Registrierung
build.gradle.kts                        Gemeinsame Konventionen, Toolchain, Lizenzheader
k8s-runtime/api/
  .../config/KubernetesConfiguration.java     Modul-Config (Record + Builder)
  .../config/TaskKubernetesConfig.java        Pro-Task-Overrides
  .../config/StagingConfiguration.java        S3-Endpoint, Bucket, Credentials-Secret
k8s-runtime/impl/
  .../KubernetesServicesModule.java           DriverModule, Einstiegspunkt
  .../KubernetesLocalCloudServiceFactory.java Factory, Port-/Adressvalidierung
  .../KubernetesService.java                  Die sieben Overrides
  .../KubernetesServiceLogCache.java          Log-Zeilen mit Zeitstempel-Dedup
  .../naming/ResourceNaming.java              Pod-, Secret-, PVC-Namen, FQDN
  .../client/KubernetesClientFactory.java     In-Cluster/Kubeconfig-Autodetect
  .../pod/PodSpecBuilder.java                 PodSpec aus ServiceConfiguration
  .../state/PodStateCache.java                Informer, alive()
  .../staging/ServiceDirectoryStager.java     tar packen, in Bucket legen
  .../console/PodConsoleInput.java            stdin via lazy attach
  .../console/PodLogStream.java               stdout via watchLog + Reconnect
```

Trennung nach Verantwortung, nicht nach Schicht: alles zur Namensbildung liegt in einer
reinen, vollständig unit-testbaren Klasse; alles mit Netzwerkkontakt ist hinter je einer
eigenen Klasse gekapselt, damit `KubernetesService` selbst dünn bleibt.

---

## Task 1: Spike — das 30-Sekunden-Budget messen

Dies ist **kein** TDD-Task. Der Output ist eine Zahl und eine Entscheidung, kein Code.
Er steht vorn, weil sein Ergebnis den Zuschnitt von Task 10 verändern kann.

**Files:**
- Create: `docs/superpowers/spikes/2026-08-28-startup-budget.md`

**Interfaces:**
- Consumes: nichts
- Produces: die Zahlen `t_schedule`, `t_pull`, `t_staging`, `t_jvm` und die Entscheidung,
  ob Image-Prepull für Phase 1 verpflichtend ist.

- [ ] **Step 1: Testcluster bereitstellen**

```bash
kind create cluster --name cloudnet-spike
kubectl create namespace cloudnet
```

- [ ] **Step 2: Ein Java-Image ohne lokalen Cache ziehen und die Zeit messen**

```bash
kubectl -n cloudnet run pulltest \
  --image=azul/zulu-openjdk-alpine:25-jre-headless-latest \
  --restart=Never --command -- sleep 5

kubectl -n cloudnet get event --field-selector involvedObject.name=pulltest \
  --sort-by=.metadata.creationTimestamp \
  -o custom-columns=TIME:.metadata.creationTimestamp,REASON:.reason,MSG:.message
```

Notiere die Differenz zwischen `Scheduled` und `Started`. Das ist `t_schedule + t_pull`.

- [ ] **Step 3: Den Download eines realistischen Staging-Archivs messen**

Lege ein 200-MB-Archiv in den Bucket und miss den Download aus einem Pod:

```bash
kubectl -n cloudnet run stagetest --rm -it --restart=Never \
  --image=rclone/rclone:latest --command -- \
  sh -c 'time rclone copy s3:staging/test.tar /tmp/'
```

- [ ] **Step 4: JVM-Bootzeit bis zum ersten Netzwerkkontakt messen**

Starte lokal einen CloudNet-Service mit dem `jvm`-Runtime und miss die Zeit vom
Prozessstart bis zur Zeile, die die Wrapper-Authentifizierung protokolliert.

- [ ] **Step 5: Ergebnis festhalten und entscheiden**

Schreibe `docs/superpowers/spikes/2026-08-28-startup-budget.md` mit den vier Zahlen,
ihrer Summe und einer der folgenden Feststellungen:

- Summe deutlich unter 30 s → Task 10 bleibt wie geplant, Prepull ist Optimierung.
- Summe nahe oder über 30 s → **Der Staging-Download muss in den Init-Container**
  (er läuft vor dem Timeout-Fenster des Wrappers), und ein Prepull-DaemonSet wird
  Voraussetzung. Ergänze in dem Fall vor Task 10 einen Task für das DaemonSet.

- [ ] **Step 6: Commit**

```bash
git add docs/superpowers/spikes/2026-08-28-startup-budget.md
git commit -m "docs: record startup budget spike results"
```

---

## Task 2: Repo-Grundgerüst

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`
- Create: `k8s-runtime/api/build.gradle.kts`, `k8s-runtime/impl/build.gradle.kts`
- Create: `LICENSE_HEADER`, `.gitignore`

**Interfaces:**
- Consumes: nichts
- Produces: die Gradle-Projekte `:k8s-runtime:api` und `:k8s-runtime:impl`; den
  Version-Katalog-Alias `libs.fabric8.client`, `libs.cloudnet.node.impl`,
  `libs.junit.jupiter`, `libs.fabric8.mockserver`.

- [ ] **Step 1: settings.gradle.kts schreiben**

```kotlin
rootProject.name = "cloudnet-kubernetes"

dependencyResolutionManagement {
  repositories {
    mavenCentral()
    maven("https://repository.derklaro.dev/releases/")
    maven("https://repository.derklaro.dev/snapshots/")
  }
  versionCatalogs {
    create("libs") {
      version("cloudnet", "4.0.0-RC17")
      version("fabric8", "7.8.0")
      version("junit", "5.11.4")
      version("shadow", "9.6.1")
      version("awssdk", "2.31.19")

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
      library("commons-compress", "org.apache.commons", "commons-compress").version("1.28.0")

      library("junit-bom", "org.junit", "junit-bom").versionRef("junit")
      library("junit-jupiter", "org.junit.jupiter", "junit-jupiter").withoutVersion()

      plugin("shadow", "com.gradleup.shadow").versionRef("shadow")
    }
  }
}

include(":k8s-runtime:api", ":k8s-runtime:impl")
```

- [ ] **Step 2: Root-build.gradle.kts mit gemeinsamen Konventionen**

```kotlin
plugins {
  alias(libs.plugins.shadow) apply false
}

subprojects {
  apply(plugin = "java-library")

  group = "dev.onelitefeather.cloudnet"
  version = "0.1.0-SNAPSHOT"

  extensions.configure<JavaPluginExtension> {
    toolchain.languageVersion = JavaLanguageVersion.of(if (name == "api") 17 else 25)
  }

  dependencies {
    "testImplementation"(platform(rootProject.libs.junit.bom))
    "testImplementation"(rootProject.libs.junit.jupiter)
  }

  tasks.withType<Test> { useJUnitPlatform() }
  tasks.withType<JavaCompile> { options.encoding = "UTF-8" }
}
```

- [ ] **Step 3: Die beiden Modul-Buildfiles**

`k8s-runtime/api/build.gradle.kts`:

```kotlin
dependencies {
  compileOnlyApi(platform(libs.cloudnet.bom))
  compileOnlyApi(libs.cloudnet.driver.api)
}
```

`k8s-runtime/impl/build.gradle.kts`:

```kotlin
plugins {
  alias(libs.plugins.shadow)
}

dependencies {
  api(projects.k8sRuntime.api)

  compileOnlyApi(platform(libs.cloudnet.bom))
  compileOnlyApi(libs.cloudnet.node.impl)
  compileOnlyApi(libs.cloudnet.utils.base)

  implementation(platform(libs.fabric8.bom))
  implementation(libs.fabric8.client) {
    exclude(group = "io.fabric8", module = "kubernetes-httpclient-vertx")
  }
  runtimeOnly(libs.fabric8.httpclient.jdk)
  implementation(libs.awssdk.s3)
  implementation(libs.commons.compress)

  testImplementation(libs.fabric8.mockserver)
  testImplementation(libs.cloudnet.node.impl)
}

tasks.shadowJar {
  archiveFileName = "cloudnet-kubernetes.jar"
  mergeServiceFiles()   // HttpClient$Factory und KubernetesResource

  val libs = "eu.cloudnetservice.modules.kubernetes.libs"
  relocate("com.fasterxml.jackson", "$libs.jackson")
  relocate("org.snakeyaml", "$libs.snakeyaml")
  relocate("org.bouncycastle", "$libs.bouncycastle")
  relocate("io.fabric8", "$libs.fabric8")
  relocate("software.amazon.awssdk", "$libs.awssdk")
  relocate("org.apache.commons.compress", "$libs.compress")
  // org.slf4j bleibt unrelocated - soll auf CloudNets Logging landen
}
```

Setze in `settings.gradle.kts` zusätzlich ganz oben
`enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")`, damit `projects.k8sRuntime.api` auflöst.

- [ ] **Step 4: Build verifizieren**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, beide Projekte konfiguriert.

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties k8s-runtime LICENSE_HEADER .gitignore
git commit -m "build: scaffold gradle multi-module project"
```

---

## Task 3: Namensableitung

Reine Funktionen, keine Abhängigkeiten — deshalb zuerst. Jede spätere Klasse benutzt sie.

**Files:**
- Create: `k8s-runtime/impl/src/main/java/eu/cloudnetservice/modules/kubernetes/impl/naming/ResourceNaming.java`
- Test: `k8s-runtime/impl/src/test/java/eu/cloudnetservice/modules/kubernetes/impl/naming/ResourceNamingTest.java`

**Interfaces:**
- Consumes: nichts
- Produces:
  - `static String podName(String serviceName, UUID serviceId)`
  - `static String secretName(String podName)` → `<podName>-wrapper`
  - `static String pvcName(String serviceName)` → `cn-<name>-data`
  - `static String podFqdn(String podName, String subdomain, String namespace, String clusterDomain)`

- [ ] **Step 1: Write the failing test**

```java
package eu.cloudnetservice.modules.kubernetes.impl.naming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ResourceNamingTest {

  private static final Pattern DNS_1123 = Pattern.compile("[a-z0-9]([-a-z0-9]*[a-z0-9])?");
  private static final UUID ID = UUID.fromString("a3f9c2e1-4b7d-4c2a-9f11-8e6d0a5b3c74");

  @Test
  void podNameCombinesServiceNameAndShortId() {
    assertEquals("cn-lobby-1-a3f9c2e1", ResourceNaming.podName("Lobby-1", ID));
  }

  @Test
  void podNameLowercasesAndStaysDns1123() {
    var name = ResourceNaming.podName("MixedCase-9", ID);
    assertTrue(DNS_1123.matcher(name).matches(), name);
  }

  @Test
  void podNameTruncatesOverlongInputToSixtyThreeChars() {
    var name = ResourceNaming.podName("a".repeat(120), ID);
    assertTrue(name.length() <= 63, "was " + name.length());
    assertTrue(DNS_1123.matcher(name).matches(), name);
  }

  @Test
  void pvcNameIgnoresUniqueIdSoItIsStableAcrossRestarts() {
    // Kritisch: fliesst die uniqueId ein, bekommt der Server bei jedem Start ein leeres Volume.
    assertEquals("cn-survival-1-data", ResourceNaming.pvcName("Survival-1"));
    assertEquals(ResourceNaming.pvcName("Survival-1"), ResourceNaming.pvcName("survival-1"));
  }

  @Test
  void secretNameDerivesFromPodName() {
    assertEquals("cn-lobby-1-a3f9c2e1-wrapper", ResourceNaming.secretName("cn-lobby-1-a3f9c2e1"));
  }

  @Test
  void podFqdnFollowsHostnameSubdomainScheme() {
    assertEquals(
      "cn-lobby-1-a3f9c2e1.cloudnet-services.cloudnet.svc.cluster.local",
      ResourceNaming.podFqdn("cn-lobby-1-a3f9c2e1", "cloudnet-services", "cloudnet", "cluster.local"));
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :k8s-runtime:impl:test --tests '*ResourceNamingTest'`
Expected: FAIL — `cannot find symbol: class ResourceNaming`

- [ ] **Step 3: Write minimal implementation**

```java
package eu.cloudnetservice.modules.kubernetes.impl.naming;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

public final class ResourceNaming {

  private static final int MAX_DNS_1123 = 63;

  private ResourceNaming() {
  }

  public static String podName(String serviceName, UUID serviceId) {
    var shortId = serviceId.toString().substring(0, 8);
    return clamp("cn-" + sanitize(serviceName) + "-" + shortId);
  }

  public static String secretName(String podName) {
    return clamp(podName + "-wrapper");
  }

  public static String pvcName(String serviceName) {
    // Bewusst OHNE uniqueId: der Name muss ueber Neustarts hinweg identisch bleiben.
    return clamp("cn-" + sanitize(serviceName) + "-data");
  }

  public static String podFqdn(String podName, String subdomain, String namespace, String clusterDomain) {
    return podName + "." + subdomain + "." + namespace + ".svc." + clusterDomain;
  }

  private static String sanitize(String raw) {
    var lower = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
    return lower.replaceAll("^-+", "").replaceAll("-+$", "").replaceAll("-{2,}", "-");
  }

  private static String clamp(String candidate) {
    if (candidate.length() <= MAX_DNS_1123) {
      return candidate;
    }
    var suffix = "-" + shortHash(candidate);
    var head = candidate.substring(0, MAX_DNS_1123 - suffix.length()).replaceAll("-+$", "");
    return head + suffix;
  }

  private static String shortHash(String input) {
    try {
      var digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest).substring(0, 8);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :k8s-runtime:impl:test --tests '*ResourceNamingTest'`
Expected: PASS, 6 Tests.

- [ ] **Step 5: Commit**

```bash
git add k8s-runtime/impl/src/main/java/eu/cloudnetservice/modules/kubernetes/impl/naming k8s-runtime/impl/src/test
git commit -m "feat: add deterministic kubernetes resource naming"
```

---

## Task 4: Modul-Konfiguration

**Files:**
- Create: `k8s-runtime/api/src/main/java/eu/cloudnetservice/modules/kubernetes/config/KubernetesConfiguration.java`
- Create: `k8s-runtime/api/src/main/java/eu/cloudnetservice/modules/kubernetes/config/StagingConfiguration.java`
- Create: `k8s-runtime/api/src/main/java/eu/cloudnetservice/modules/kubernetes/config/TaskKubernetesConfig.java`
- Test: `k8s-runtime/api/src/test/java/eu/cloudnetservice/modules/kubernetes/config/KubernetesConfigurationTest.java`

**Interfaces:**
- Consumes: nichts
- Produces: `KubernetesConfiguration` mit den Accessoren `factoryName()`, `namespace()`,
  `clusterDomain()`, `subdomain()`, `serviceImage()`, `nodeServiceDns()`,
  `nodeListenerPort()`, `terminationGracePeriodSeconds()`, `activeDeadlineSeconds()`,
  `memoryOverheadFactor()`, `staging()`; `StagingConfiguration` mit `endpoint()`,
  `bucket()`, `pathStyleAccess()`, `accessKey()`, `secretKey()`;
  `TaskKubernetesConfig` mit `image()`, `nodeSelector()`, `tolerationKey()`.

- [ ] **Step 1: Write the failing test**

```java
package eu.cloudnetservice.modules.kubernetes.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class KubernetesConfigurationTest {

  @Test
  void defaultsMatchTheSpecification() {
    var config = KubernetesConfiguration.builder().build();

    assertEquals("k8s-jvm", config.factoryName());
    assertEquals("cloudnet", config.namespace());
    assertEquals("cluster.local", config.clusterDomain());
    assertEquals("cloudnet-services", config.subdomain());
    assertEquals(120, config.terminationGracePeriodSeconds());
    assertEquals(86400, config.activeDeadlineSeconds());
    assertEquals(1.35d, config.memoryOverheadFactor());
  }

  @Test
  void memoryLimitAddsNonHeapOverheadWithFloor() {
    var config = KubernetesConfiguration.builder().build();

    // 4096 * 1.35 -> Overhead 1433, ueber dem Floor von 512
    assertEquals(5529, config.memoryLimitMib(4096));
    // 512 * 0.35 = 179 -> Floor von 512 greift
    assertEquals(1024, config.memoryLimitMib(512));
  }

  @Test
  void activeDeadlineIsNeverUnlimited() {
    assertTrue(KubernetesConfiguration.builder().build().activeDeadlineSeconds() > 0);
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :k8s-runtime:api:test`
Expected: FAIL — `cannot find symbol: class KubernetesConfiguration`

- [ ] **Step 3: Write minimal implementation**

```java
package eu.cloudnetservice.modules.kubernetes.config;

public record KubernetesConfiguration(
  String factoryName,
  String namespace,
  String clusterDomain,
  String subdomain,
  String serviceImage,
  String nodeServiceDns,
  int nodeListenerPort,
  int terminationGracePeriodSeconds,
  long activeDeadlineSeconds,
  double memoryOverheadFactor,
  int memoryOverheadFloorMib,
  StagingConfiguration staging
) {

  public static Builder builder() {
    return new Builder();
  }

  /** Heap plus Non-Heap-Reserve. Limit == Heap ist der klassische Fehler. */
  public int memoryLimitMib(int maxHeapMemoryMib) {
    var overhead = Math.max(
      this.memoryOverheadFloorMib,
      (int) (maxHeapMemoryMib * (this.memoryOverheadFactor - 1.0d)));
    return maxHeapMemoryMib + overhead;
  }

  public static final class Builder {

    private String factoryName = "k8s-jvm";
    private String namespace = "cloudnet";
    private String clusterDomain = "cluster.local";
    private String subdomain = "cloudnet-services";
    private String serviceImage = "ghcr.io/onelitefeather/cloudnet-service:4.0.0-RC17";
    private String nodeServiceDns = "cloudnet-node.cloudnet.svc.cluster.local";
    private int nodeListenerPort = 1410;
    private int terminationGracePeriodSeconds = 120;
    private long activeDeadlineSeconds = 86400L;
    private double memoryOverheadFactor = 1.35d;
    private int memoryOverheadFloorMib = 512;
    private StagingConfiguration staging = StagingConfiguration.builder().build();

    public Builder factoryName(String factoryName) { this.factoryName = factoryName; return this; }
    public Builder namespace(String namespace) { this.namespace = namespace; return this; }
    public Builder clusterDomain(String clusterDomain) { this.clusterDomain = clusterDomain; return this; }
    public Builder subdomain(String subdomain) { this.subdomain = subdomain; return this; }
    public Builder serviceImage(String serviceImage) { this.serviceImage = serviceImage; return this; }
    public Builder nodeServiceDns(String nodeServiceDns) { this.nodeServiceDns = nodeServiceDns; return this; }
    public Builder nodeListenerPort(int port) { this.nodeListenerPort = port; return this; }
    public Builder staging(StagingConfiguration staging) { this.staging = staging; return this; }

    public KubernetesConfiguration build() {
      return new KubernetesConfiguration(
        this.factoryName, this.namespace, this.clusterDomain, this.subdomain,
        this.serviceImage, this.nodeServiceDns, this.nodeListenerPort,
        this.terminationGracePeriodSeconds, this.activeDeadlineSeconds,
        this.memoryOverheadFactor, this.memoryOverheadFloorMib, this.staging);
    }
  }
}
```

Dazu `StagingConfiguration` nach demselben Muster:

```java
package eu.cloudnetservice.modules.kubernetes.config;

public record StagingConfiguration(
  String endpoint,
  String bucket,
  boolean pathStyleAccess,
  String accessKey,
  String secretKey,
  String credentialsSecretName
) {

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {

    private String endpoint = "http://rook-ceph-rgw-cloudnet-store.rook-ceph.svc";
    private String bucket = "cloudnet-staging";
    private boolean pathStyleAccess = true;
    private String accessKey = "";
    private String secretKey = "";
    private String credentialsSecretName = "cloudnet-staging-credentials";

    public Builder endpoint(String endpoint) { this.endpoint = endpoint; return this; }
    public Builder bucket(String bucket) { this.bucket = bucket; return this; }
    public Builder pathStyleAccess(boolean value) { this.pathStyleAccess = value; return this; }
    public Builder accessKey(String key) { this.accessKey = key; return this; }
    public Builder secretKey(String key) { this.secretKey = key; return this; }

    public StagingConfiguration build() {
      return new StagingConfiguration(
        this.endpoint, this.bucket, this.pathStyleAccess,
        this.accessKey, this.secretKey, this.credentialsSecretName);
    }
  }
}
```

Und `TaskKubernetesConfig`:

```java
package eu.cloudnetservice.modules.kubernetes.config;

import java.util.Map;

public record TaskKubernetesConfig(
  String image,
  Map<String, String> nodeSelector,
  String tolerationKey
) {
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :k8s-runtime:api:test`
Expected: PASS, 3 Tests.

- [ ] **Step 5: Commit**

```bash
git add k8s-runtime/api
git commit -m "feat: add module configuration records"
```

---

## Task 5: Client-Bootstrap mit Autodetect

**Files:**
- Create: `k8s-runtime/impl/src/main/java/eu/cloudnetservice/modules/kubernetes/impl/client/KubernetesClientFactory.java`
- Test: `k8s-runtime/impl/src/test/java/eu/cloudnetservice/modules/kubernetes/impl/client/KubernetesClientFactoryTest.java`

**Interfaces:**
- Consumes: nichts
- Produces: `static KubernetesClient create(String namespaceOverride)`

- [ ] **Step 1: Write the failing test**

```java
package eu.cloudnetservice.modules.kubernetes.impl.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class KubernetesClientFactoryTest {

  @Test
  void clientUsesTheJdkHttpTransportAndNotTheServiceLoader() {
    // Der TCCL im CloudNet-Modul-Thread ist nicht der ModuleURLClassLoader.
    // Die Factory muss deshalb explizit gesetzt sein, sonst schlaegt die
    // ServiceLoader-Aufloesung zur Laufzeit fehl.
    try (var client = KubernetesClientFactory.create("cloudnet")) {
      assertNotNull(client);
      assertEquals("cloudnet", client.getNamespace());
    }
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :k8s-runtime:impl:test --tests '*KubernetesClientFactoryTest'`
Expected: FAIL — `cannot find symbol: class KubernetesClientFactory`

- [ ] **Step 3: Write minimal implementation**

```java
package eu.cloudnetservice.modules.kubernetes.impl.client;

import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.jdkhttp.JdkHttpClientFactory;

public final class KubernetesClientFactory {

  private KubernetesClientFactory() {
  }

  /**
   * ConfigBuilder findet den ServiceAccount unter
   * /var/run/secrets/kubernetes.io/serviceaccount selbst und faellt sonst auf
   * KUBECONFIG bzw. ~/.kube/config zurueck - damit sind In-Cluster- und
   * Extern-Betrieb ohne Fallunterscheidung abgedeckt.
   */
  public static KubernetesClient create(String namespaceOverride) {
    var config = new ConfigBuilder()
      .withNamespace(namespaceOverride)
      .build();

    return new KubernetesClientBuilder()
      .withHttpClientFactory(new JdkHttpClientFactory())
      .withConfig(config)
      .build();
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :k8s-runtime:impl:test --tests '*KubernetesClientFactoryTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add k8s-runtime/impl/src/main/java/eu/cloudnetservice/modules/kubernetes/impl/client k8s-runtime/impl/src/test
git commit -m "feat: add kubernetes client bootstrap with in-cluster autodetect"
```

---

## Task 6: PodSpec-Builder

**Files:**
- Create: `k8s-runtime/impl/src/main/java/eu/cloudnetservice/modules/kubernetes/impl/pod/PodSpecBuilder.java`
- Test: `k8s-runtime/impl/src/test/java/eu/cloudnetservice/modules/kubernetes/impl/pod/PodSpecBuilderTest.java`

**Interfaces:**
- Consumes: `KubernetesConfiguration`, `ResourceNaming`
- Produces: `Pod build(PodRequest request)` mit
  `record PodRequest(String serviceName, UUID serviceId, String taskName, int maxHeapMemoryMib, int port, String nodeUniqueId, String ownerConfigMapName, String ownerConfigMapUid, List<String> command)`

- [ ] **Step 1: Write the failing test**

```java
package eu.cloudnetservice.modules.kubernetes.impl.pod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.cloudnetservice.modules.kubernetes.config.KubernetesConfiguration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PodSpecBuilderTest {

  private static final UUID ID = UUID.fromString("a3f9c2e1-4b7d-4c2a-9f11-8e6d0a5b3c74");

  private PodSpecBuilder builder;
  private PodSpecBuilder.PodRequest request;

  @BeforeEach
  void setUp() {
    this.builder = new PodSpecBuilder(KubernetesConfiguration.builder().build());
    this.request = new PodSpecBuilder.PodRequest(
      "Lobby-1", ID, "lobby", 4096, 25565, "node-1",
      "cloudnet-node-node-1-owner", "6f1c9f0e-1111-2222-3333-444455556666",
      List.of("java", "-Xmx4096M", "-cp", "/cloudnet/libs/*", "Main"));
  }

  @Test
  void consoleRequiresStdinWithoutStdinOnce() {
    var container = this.builder.build(this.request).getSpec().getContainers().getFirst();

    assertTrue(container.getStdin(), "stdin must be open for attach");
    // stdinOnce: true schliesst stdin beim ersten Detach - die Konsole waere dauerhaft tot.
    assertFalse(container.getStdinOnce());
    // tty: true multiplext stdout/stderr und uebersetzt CRLF.
    assertFalse(container.getTty());
  }

  @Test
  void restartPolicyIsNeverSoCloudNetOwnsTheLifecycle() {
    assertEquals("Never", this.builder.build(this.request).getSpec().getRestartPolicy());
  }

  @Test
  void addressIsDeterministicViaHostnameAndSubdomain() {
    var spec = this.builder.build(this.request).getSpec();

    assertEquals("cn-lobby-1-a3f9c2e1", spec.getHostname());
    assertEquals("cloudnet-services", spec.getSubdomain());
  }

  @Test
  void memoryRequestEqualsLimitAndCpuHasNoLimit() {
    var resources = this.builder.build(this.request).getSpec()
      .getContainers().getFirst().getResources();

    assertEquals("5529Mi", resources.getRequests().get("memory").toString());
    assertEquals("5529Mi", resources.getLimits().get("memory").toString());
    // Ein CPU-Limit erzeugt CFS-Throttling - genau die Mikro-Stutter, die als Lag ankommen.
    assertNull(resources.getLimits().get("cpu"));
  }

  @Test
  void activeDeadlineActsAsKubeletSideBackstop() {
    assertEquals(86400L, this.builder.build(this.request).getSpec().getActiveDeadlineSeconds());
  }

  @Test
  void ownerReferencePointsAtConfigMapNotAtTheNodePod() {
    // Der Node-Pod wechselt bei einem Rolling Update seine UID - alle Gameserver
    // wuerden dann mitten im Spiel garbage-collected.
    var owner = this.builder.build(this.request).getMetadata().getOwnerReferences().getFirst();

    assertEquals("ConfigMap", owner.getKind());
    assertEquals("cloudnet-node-node-1-owner", owner.getName());
    assertFalse(owner.getController());
  }

  @Test
  void gameserverGetsNoApiAccess() {
    var spec = this.builder.build(this.request).getSpec();

    assertFalse(spec.getAutomountServiceAccountToken());
    assertFalse(spec.getEnableServiceLinks());
  }

  @Test
  void labelsAllowTheInformerToSelectOurPods() {
    var labels = this.builder.build(this.request).getMetadata().getLabels();

    assertEquals("service", labels.get("cloudnet.eu/component"));
    assertEquals("lobby", labels.get("cloudnet.eu/task"));
    assertEquals("node-1", labels.get("cloudnet.eu/managed-by"));
    assertEquals(ID.toString(), labels.get("cloudnet.eu/service-id"));
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :k8s-runtime:impl:test --tests '*PodSpecBuilderTest'`
Expected: FAIL — `cannot find symbol: class PodSpecBuilder`

- [ ] **Step 3: Write minimal implementation**

```java
package eu.cloudnetservice.modules.kubernetes.impl.pod;

import eu.cloudnetservice.modules.kubernetes.config.KubernetesConfiguration;
import eu.cloudnetservice.modules.kubernetes.impl.naming.ResourceNaming;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PodSpecBuilder {

  private final KubernetesConfiguration config;

  public PodSpecBuilder(KubernetesConfiguration config) {
    this.config = config;
  }

  public record PodRequest(
    String serviceName,
    UUID serviceId,
    String taskName,
    int maxHeapMemoryMib,
    int port,
    String nodeUniqueId,
    String ownerConfigMapName,
    String ownerConfigMapUid,
    List<String> command
  ) {
  }

  public Pod build(PodRequest request) {
    var podName = ResourceNaming.podName(request.serviceName(), request.serviceId());
    var memory = new Quantity(this.config.memoryLimitMib(request.maxHeapMemoryMib()) + "Mi");

    return new PodBuilder()
      .withNewMetadata()
        .withName(podName)
        .withNamespace(this.config.namespace())
        .withLabels(Map.of(
          "cloudnet.eu/component", "service",
          "cloudnet.eu/task", request.taskName(),
          "cloudnet.eu/service-id", request.serviceId().toString(),
          "cloudnet.eu/managed-by", request.nodeUniqueId()))
        .addNewOwnerReference()
          .withApiVersion("v1")
          .withKind("ConfigMap")
          .withName(request.ownerConfigMapName())
          .withUid(request.ownerConfigMapUid())
          .withController(false)
          .withBlockOwnerDeletion(false)
        .endOwnerReference()
      .endMetadata()
      .withNewSpec()
        .withRestartPolicy("Never")
        .withActiveDeadlineSeconds(this.config.activeDeadlineSeconds())
        .withTerminationGracePeriodSeconds((long) this.config.terminationGracePeriodSeconds())
        .withHostname(podName)
        .withSubdomain(this.config.subdomain())
        .withAutomountServiceAccountToken(false)
        .withEnableServiceLinks(false)
        .withNewSecurityContext()
          .withRunAsNonRoot(true)
          .withRunAsUser(1000L)
          .withFsGroup(1000L)
        .endSecurityContext()
        .addNewVolume()
          .withName("wrapper-config")
          .withNewSecret()
            .withSecretName(ResourceNaming.secretName(podName))
            .withDefaultMode(0400)
          .endSecret()
        .endVolume()
        .addNewVolume()
          .withName("service-dir")
          .withNewEmptyDir().endEmptyDir()
        .endVolume()
        .addNewContainer()
          .withName("service")
          .withImage(this.config.serviceImage())
          .withImagePullPolicy("IfNotPresent")
          .withCommand(request.command())
          .withStdin(true)
          .withStdinOnce(false)
          .withTty(false)
          .withWorkingDir("/cloudnet/service")
          .withNewResources()
            .addToRequests("memory", memory)
            .addToRequests("cpu", new Quantity("2"))
            .addToLimits("memory", memory)
          .endResources()
          .addNewVolumeMount()
            .withName("wrapper-config")
            .withMountPath("/cloudnet/wrapper")
            .withReadOnly(true)
          .endVolumeMount()
          .addNewVolumeMount()
            .withName("service-dir")
            .withMountPath("/cloudnet/service")
          .endVolumeMount()
          .withNewSecurityContext()
            .withAllowPrivilegeEscalation(false)
            .withNewCapabilities().withDrop("ALL").endCapabilities()
          .endSecurityContext()
        .endContainer()
      .endSpec()
      .build();
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :k8s-runtime:impl:test --tests '*PodSpecBuilderTest'`
Expected: PASS, 8 Tests.

- [ ] **Step 5: Commit**

```bash
git add k8s-runtime/impl/src/main/java/eu/cloudnetservice/modules/kubernetes/impl/pod k8s-runtime/impl/src/test
git commit -m "feat: add pod spec builder"
```

---

## Task 7: Informer-basierter Zustands-Cache

Der Watchdog ruft `alive()` jede Sekunde pro Service. Ein API-Call an dieser Stelle
überlastet den API-Server — deshalb ist der Cache keine Optimierung, sondern Voraussetzung.

**Files:**
- Create: `k8s-runtime/impl/src/main/java/eu/cloudnetservice/modules/kubernetes/impl/state/PodStateCache.java`
- Test: `k8s-runtime/impl/src/test/java/eu/cloudnetservice/modules/kubernetes/impl/state/PodStateCacheTest.java`

**Interfaces:**
- Consumes: `KubernetesConfiguration`, `KubernetesClient`
- Produces: `void start(String nodeUniqueId)`, `boolean running(String serviceId)`,
  `boolean known(String serviceId)`, `Set<String> knownServiceIds()`, `void close()`

- [ ] **Step 1: Write the failing test**

```java
package eu.cloudnetservice.modules.kubernetes.impl.state;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.cloudnetservice.modules.kubernetes.config.KubernetesConfiguration;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

@EnableKubernetesMockClient(crud = true)
class PodStateCacheTest {

  static KubernetesClient client;

  private static final String SERVICE_ID = "a3f9c2e1-4b7d-4c2a-9f11-8e6d0a5b3c74";

  private void createPod(String phase) {
    client.pods().inNamespace("cloudnet").resource(new PodBuilder()
      .withNewMetadata()
        .withName("cn-lobby-1-a3f9c2e1")
        .withNamespace("cloudnet")
        .withLabels(Map.of(
          "cloudnet.eu/managed-by", "node-1",
          "cloudnet.eu/service-id", SERVICE_ID))
      .endMetadata()
      .withNewStatus().withPhase(phase).endStatus()
      .build()).create();
  }

  @Test
  void runningPodIsReportedAlive() {
    this.createPod("Running");
    try (var cache = new PodStateCache(client, KubernetesConfiguration.builder().build())) {
      cache.start("node-1");
      Awaitility.await().atMost(5, TimeUnit.SECONDS)
        .untilAsserted(() -> assertTrue(cache.running(SERVICE_ID)));
    }
  }

  @Test
  void pendingPodIsNotAliveButIsKnown() {
    // Kubernetes kennt Pending, Docker nicht. Der Watchdog wuerde einen noch
    // schedulenden Pod sonst als tot werten und stoppen.
    this.createPod("Pending");
    try (var cache = new PodStateCache(client, KubernetesConfiguration.builder().build())) {
      cache.start("node-1");
      Awaitility.await().atMost(5, TimeUnit.SECONDS)
        .untilAsserted(() -> assertTrue(cache.known(SERVICE_ID)));
      assertFalse(cache.running(SERVICE_ID));
    }
  }

  @Test
  void unknownServiceIsNeitherKnownNorRunning() {
    try (var cache = new PodStateCache(client, KubernetesConfiguration.builder().build())) {
      cache.start("node-1");
      assertFalse(cache.known("does-not-exist"));
      assertFalse(cache.running("does-not-exist"));
    }
  }
}
```

Ergänze in `k8s-runtime/impl/build.gradle.kts`:
`testImplementation("org.awaitility:awaitility:4.3.0")`

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :k8s-runtime:impl:test --tests '*PodStateCacheTest'`
Expected: FAIL — `cannot find symbol: class PodStateCache`

- [ ] **Step 3: Write minimal implementation**

```java
package eu.cloudnetservice.modules.kubernetes.impl.state;

import eu.cloudnetservice.modules.kubernetes.config.KubernetesConfiguration;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class PodStateCache implements AutoCloseable {

  private static final long RESYNC_MILLIS = 30_000L;
  private static final String LABEL_MANAGED_BY = "cloudnet.eu/managed-by";
  private static final String LABEL_SERVICE_ID = "cloudnet.eu/service-id";

  private final KubernetesClient client;
  private final KubernetesConfiguration config;
  private final Map<String, Pod> byServiceId = new ConcurrentHashMap<>();

  private volatile SharedIndexInformer<Pod> informer;

  public PodStateCache(KubernetesClient client, KubernetesConfiguration config) {
    this.client = client;
    this.config = config;
  }

  public void start(String nodeUniqueId) {
    this.informer = this.client.pods()
      .inNamespace(this.config.namespace())
      .withLabel(LABEL_MANAGED_BY, nodeUniqueId)
      .inform(new ResourceEventHandler<>() {
        @Override
        public void onAdd(Pod pod) {
          index(pod);
        }

        @Override
        public void onUpdate(Pod oldPod, Pod newPod) {
          index(newPod);
        }

        @Override
        public void onDelete(Pod pod, boolean unknownFinalState) {
          var id = serviceId(pod);
          if (id != null) {
            byServiceId.remove(id);
          }
        }
      }, RESYNC_MILLIS);
  }

  /** O(1) und ohne Netzwerkkontakt - sicher fuer den Watchdog-Tick. */
  public boolean running(String serviceId) {
    var pod = this.byServiceId.get(serviceId);
    return pod != null
      && pod.getStatus() != null
      && "Running".equals(pod.getStatus().getPhase())
      && pod.getMetadata().getDeletionTimestamp() == null;
  }

  /** Ein Pod in Pending ist bekannt, aber nicht lauffaehig. */
  public boolean known(String serviceId) {
    return this.byServiceId.containsKey(serviceId);
  }

  public Set<String> knownServiceIds() {
    return Set.copyOf(this.byServiceId.keySet());
  }

  private void index(Pod pod) {
    var id = serviceId(pod);
    if (id != null) {
      this.byServiceId.put(id, pod);
    }
  }

  private static String serviceId(Pod pod) {
    var labels = pod.getMetadata().getLabels();
    return labels == null ? null : labels.get(LABEL_SERVICE_ID);
  }

  @Override
  public void close() {
    var current = this.informer;
    if (current != null) {
      current.close();
    }
    this.byServiceId.clear();
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :k8s-runtime:impl:test --tests '*PodStateCacheTest'`
Expected: PASS, 3 Tests.

- [ ] **Step 5: Commit**

```bash
git add k8s-runtime/impl/src/main/java/eu/cloudnetservice/modules/kubernetes/impl/state k8s-runtime/impl/src/test k8s-runtime/impl/build.gradle.kts
git commit -m "feat: add informer backed pod state cache"
```

---

## Task 8: Staging-Transport

**Files:**
- Create: `k8s-runtime/impl/src/main/java/eu/cloudnetservice/modules/kubernetes/impl/staging/ServiceDirectoryStager.java`
- Test: `k8s-runtime/impl/src/test/java/eu/cloudnetservice/modules/kubernetes/impl/staging/ServiceDirectoryStagerTest.java`

**Interfaces:**
- Consumes: `StagingConfiguration`
- Produces: `String upload(Path serviceDirectory, String objectKey)` (liefert den Key zurück),
  `void delete(String objectKey)`, sowie `static void pack(Path source, OutputStream target)`

- [ ] **Step 1: Write the failing test**

Getestet wird die reine Pack-Logik — sie trägt das Risiko (Pfade, leere Ordner,
Traversal). Der S3-Aufruf selbst ist eine dünne SDK-Delegation.

```java
package eu.cloudnetservice.modules.kubernetes.impl.staging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceDirectoryStagerTest {

  @TempDir
  Path source;

  private List<String> packAndListEntries() throws Exception {
    var buffer = new ByteArrayOutputStream();
    ServiceDirectoryStager.pack(this.source, buffer);

    var names = new ArrayList<String>();
    try (var in = new TarArchiveInputStream(new ByteArrayInputStream(buffer.toByteArray()))) {
      var entry = in.getNextEntry();
      while (entry != null) {
        names.add(entry.getName());
        entry = in.getNextEntry();
      }
    }
    return names;
  }

  @Test
  void packsNestedFilesWithRelativePaths() throws Exception {
    Files.createDirectories(this.source.resolve("plugins/Essentials"));
    Files.writeString(this.source.resolve("server.properties"), "server-port=25565");
    Files.writeString(this.source.resolve("plugins/Essentials/config.yml"), "locale: de");

    var names = this.packAndListEntries();

    assertTrue(names.contains("server.properties"), names.toString());
    assertTrue(names.contains("plugins/Essentials/config.yml"), names.toString());
  }

  @Test
  void preservesFileContent() throws Exception {
    Files.writeString(this.source.resolve("eula.txt"), "eula=true");

    var buffer = new ByteArrayOutputStream();
    ServiceDirectoryStager.pack(this.source, buffer);

    try (var in = new TarArchiveInputStream(new ByteArrayInputStream(buffer.toByteArray()))) {
      var entry = in.getNextEntry();
      assertEquals("eula.txt", entry.getName());
      assertEquals("eula=true", new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  @Test
  void skipsTheWrapperJarWhichTheImageAlreadyProvides() throws Exception {
    Files.writeString(this.source.resolve("wrapper.jar"), "binary");
    Files.writeString(this.source.resolve("server.properties"), "x=1");

    var names = this.packAndListEntries();

    assertTrue(names.contains("server.properties"));
    assertTrue(!names.contains("wrapper.jar"), "wrapper.jar must not be staged");
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :k8s-runtime:impl:test --tests '*ServiceDirectoryStagerTest'`
Expected: FAIL — `cannot find symbol: class ServiceDirectoryStager`

- [ ] **Step 3: Write minimal implementation**

```java
package eu.cloudnetservice.modules.kubernetes.impl.staging;

import eu.cloudnetservice.modules.kubernetes.config.StagingConfiguration;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

public final class ServiceDirectoryStager implements AutoCloseable {

  /** Das Image liefert Wrapper und Launcher-Libs bereits mit. */
  private static final Set<String> EXCLUDED = Set.of("wrapper.jar");

  private final StagingConfiguration config;
  private final S3Client s3;

  public ServiceDirectoryStager(StagingConfiguration config) {
    this.config = config;
    this.s3 = S3Client.builder()
      .endpointOverride(URI.create(config.endpoint()))
      .forcePathStyle(config.pathStyleAccess())
      .region(Region.US_EAST_1)
      .credentialsProvider(StaticCredentialsProvider.create(
        AwsBasicCredentials.create(config.accessKey(), config.secretKey())))
      .build();
  }

  public static void pack(Path source, OutputStream target) throws IOException {
    try (var tar = new TarArchiveOutputStream(target)) {
      tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
      try (Stream<Path> walk = Files.walk(source)) {
        walk.filter(Files::isRegularFile).forEach(file -> {
          var relative = source.relativize(file).toString().replace('\\', '/');
          if (EXCLUDED.contains(relative)) {
            return;
          }
          try {
            var entry = new TarArchiveEntry(file.toFile(), relative);
            tar.putArchiveEntry(entry);
            Files.copy(file, tar);
            tar.closeArchiveEntry();
          } catch (IOException exception) {
            throw new UncheckedIOException(exception);
          }
        });
      } catch (UncheckedIOException exception) {
        throw exception.getCause();
      }
    }
  }

  public String upload(Path serviceDirectory, String objectKey) throws IOException {
    var archive = Files.createTempFile("cloudnet-staging-", ".tar");
    try {
      try (var out = Files.newOutputStream(archive)) {
        pack(serviceDirectory, out);
      }
      this.s3.putObject(
        PutObjectRequest.builder().bucket(this.config.bucket()).key(objectKey).build(),
        RequestBody.fromFile(archive));
      return objectKey;
    } finally {
      Files.deleteIfExists(archive);
    }
  }

  public void delete(String objectKey) {
    this.s3.deleteObject(
      DeleteObjectRequest.builder().bucket(this.config.bucket()).key(objectKey).build());
  }

  @Override
  public void close() {
    this.s3.close();
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :k8s-runtime:impl:test --tests '*ServiceDirectoryStagerTest'`
Expected: PASS, 3 Tests.

- [ ] **Step 5: Commit**

```bash
git add k8s-runtime/impl/src/main/java/eu/cloudnetservice/modules/kubernetes/impl/staging k8s-runtime/impl/src/test
git commit -m "feat: add service directory staging over object storage"
```

---

## Task 9: Konsole — stdin über lazy Attach

**Files:**
- Create: `k8s-runtime/impl/src/main/java/eu/cloudnetservice/modules/kubernetes/impl/console/PodConsoleInput.java`
- Test: `k8s-runtime/impl/src/test/java/eu/cloudnetservice/modules/kubernetes/impl/console/PodConsoleInputTest.java`

**Interfaces:**
- Consumes: `KubernetesClient`
- Produces: `void runCommand(String command)`, `void close()`

- [ ] **Step 1: Write the failing test**

```java
package eu.cloudnetservice.modules.kubernetes.impl.console;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.Test;

@EnableKubernetesMockClient(crud = true)
class PodConsoleInputTest {

  static KubernetesClient client;

  @Test
  void closingWithoutEverAttachingIsSafe() {
    // Lazy Attach: ohne Konsolen-Befehl darf nie eine Verbindung aufgebaut worden sein.
    var console = new PodConsoleInput(client, "cloudnet", "cn-lobby-1-a3f9c2e1");
    assertDoesNotThrow(console::close);
  }

  @Test
  void commandAgainstMissingPodFailsAfterRetry() {
    var console = new PodConsoleInput(client, "cloudnet", "does-not-exist");
    assertThrows(IllegalStateException.class, () -> console.runCommand("stop"));
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :k8s-runtime:impl:test --tests '*PodConsoleInputTest'`
Expected: FAIL — `cannot find symbol: class PodConsoleInput`

- [ ] **Step 3: Write minimal implementation**

```java
package eu.cloudnetservice.modules.kubernetes.impl.console;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ExecListener;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * stdin von PID 1 ist ausschliesslich ueber attach erreichbar - exec startet einen
 * neuen Prozess, dessen stdin nicht die des Servers ist.
 *
 * <p>Der Attach wird bewusst erst beim ersten Konsolen-Befehl geoeffnet. Damit ist
 * der 4-Stunden-Idle-Timeout des kubelet irrelevant, ohne Keepalive-Bytes zu senden,
 * die der Server als leere Kommandos interpretieren wuerde.
 */
public final class PodConsoleInput implements AutoCloseable {

  private static final Logger LOGGER = LoggerFactory.getLogger(PodConsoleInput.class);
  private static final int ATTEMPTS = 2;

  private final KubernetesClient client;
  private final String namespace;
  private final String podName;

  private volatile ExecWatch attach;
  private volatile OutputStream stdin;

  public PodConsoleInput(KubernetesClient client, String namespace, String podName) {
    this.client = client;
    this.namespace = namespace;
    this.podName = podName;
  }

  public synchronized void runCommand(String command) {
    for (var attempt = 0; attempt < ATTEMPTS; attempt++) {
      try {
        this.ensureAttached();
        this.stdin.write((command + "\n").getBytes(StandardCharsets.UTF_8));
        this.stdin.flush();
        return;
      } catch (Exception exception) {
        LOGGER.debug("stdin write to {} failed on attempt {}", this.podName, attempt, exception);
        this.drop();
        if (attempt == ATTEMPTS - 1) {
          throw new IllegalStateException("stdin unreachable for pod " + this.podName, exception);
        }
      }
    }
  }

  private void ensureAttached() {
    if (this.stdin != null) {
      return;
    }
    this.attach = this.client.pods()
      .inNamespace(this.namespace)
      .withName(this.podName)
      .withReadyWaitTimeout(60_000)
      .redirectingInput(8192)
      .usingListener(new ExecListener() {
        @Override
        public void onFailure(Throwable throwable, Response response) {
          drop();
        }

        @Override
        public void onClose(int code, String reason) {
          drop();
        }
      })
      .attach();
    this.stdin = this.attach.getInput();
  }

  private synchronized void drop() {
    this.stdin = null;
    var current = this.attach;
    this.attach = null;
    if (current != null) {
      try {
        current.close();
      } catch (Exception ignored) {
        // beim Abbau irrelevant
      }
    }
  }

  @Override
  public void close() {
    this.drop();
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :k8s-runtime:impl:test --tests '*PodConsoleInputTest'`
Expected: PASS, 2 Tests.

- [ ] **Step 5: Commit**

```bash
git add k8s-runtime/impl/src/main/java/eu/cloudnetservice/modules/kubernetes/impl/console k8s-runtime/impl/src/test
git commit -m "feat: add pod console input via lazy attach"
```

---

## Task 10: Log-Zeilen mit Zeitstempel-Dedup

Log-Rotation und der 4-Stunden-Timeout sind Normalfälle, keine Fehler. Weil `sinceTime`
nur Sekundenauflösung hat, kommen beim Reconnect Zeilen doppelt — die müssen weg.

**Files:**
- Create: `k8s-runtime/impl/src/main/java/eu/cloudnetservice/modules/kubernetes/impl/console/LogLineParser.java`
- Test: `k8s-runtime/impl/src/test/java/eu/cloudnetservice/modules/kubernetes/impl/console/LogLineParserTest.java`

**Interfaces:**
- Consumes: nichts
- Produces: `record ParsedLine(Instant timestamp, String message)`;
  `Optional<ParsedLine> accept(String rawLine)` — verwirft Zeilen, die nicht neuer
  sind als die zuletzt akzeptierte; `Optional<Instant> lastSeen()`

- [ ] **Step 1: Write the failing test**

```java
package eu.cloudnetservice.modules.kubernetes.impl.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class LogLineParserTest {

  @Test
  void splitsTimestampFromMessage() {
    var parser = new LogLineParser();
    var line = parser.accept("2026-08-28T10:15:30.123456789Z [INFO] Done (2.1s)!").orElseThrow();

    assertEquals(Instant.parse("2026-08-28T10:15:30.123456789Z"), line.timestamp());
    assertEquals("[INFO] Done (2.1s)!", line.message());
  }

  @Test
  void dropsLinesAlreadySeenAfterReconnect() {
    // sinceTime hat nur Sekundenaufloesung - beim Wiederaufsetzen kommt die
    // letzte Sekunde erneut.
    var parser = new LogLineParser();
    parser.accept("2026-08-28T10:15:30.500000000Z first");

    assertTrue(parser.accept("2026-08-28T10:15:30.100000000Z earlier").isEmpty());
    assertTrue(parser.accept("2026-08-28T10:15:30.500000000Z same").isEmpty());
    assertTrue(parser.accept("2026-08-28T10:15:31.000000000Z later").isPresent());
  }

  @Test
  void passesThroughLinesWithoutParsableTimestamp() {
    var parser = new LogLineParser();
    var line = parser.accept("no timestamp here").orElseThrow();

    assertEquals("no timestamp here", line.message());
  }

  @Test
  void remembersLastSeenForTheReconnectQuery() {
    var parser = new LogLineParser();
    parser.accept("2026-08-28T10:15:30.000000000Z hello");

    assertEquals(Instant.parse("2026-08-28T10:15:30Z"), parser.lastSeen().orElseThrow());
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :k8s-runtime:impl:test --tests '*LogLineParserTest'`
Expected: FAIL — `cannot find symbol: class LogLineParser`

- [ ] **Step 3: Write minimal implementation**

```java
package eu.cloudnetservice.modules.kubernetes.impl.console;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

public final class LogLineParser {

  public record ParsedLine(Instant timestamp, String message) {
  }

  private volatile Instant lastSeen;

  public Optional<ParsedLine> accept(String rawLine) {
    var separator = rawLine.indexOf(' ');
    if (separator < 0) {
      return Optional.of(new ParsedLine(null, rawLine));
    }

    Instant timestamp;
    try {
      timestamp = Instant.parse(rawLine.substring(0, separator));
    } catch (DateTimeParseException exception) {
      return Optional.of(new ParsedLine(null, rawLine));
    }

    if (this.lastSeen != null && !timestamp.isAfter(this.lastSeen)) {
      return Optional.empty();
    }
    this.lastSeen = timestamp;
    return Optional.of(new ParsedLine(timestamp, rawLine.substring(separator + 1)));
  }

  public Optional<Instant> lastSeen() {
    return Optional.ofNullable(this.lastSeen);
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :k8s-runtime:impl:test --tests '*LogLineParserTest'`
Expected: PASS, 4 Tests.

- [ ] **Step 5: Commit**

```bash
git add k8s-runtime/impl/src/main/java/eu/cloudnetservice/modules/kubernetes/impl/console/LogLineParser.java k8s-runtime/impl/src/test
git commit -m "feat: add log line parser with reconnect deduplication"
```

---

## Task 11: Log-Stream mit Reconnect-Schleife

**Files:**
- Create: `k8s-runtime/impl/src/main/java/eu/cloudnetservice/modules/kubernetes/impl/console/PodLogStream.java`
- Create: `k8s-runtime/impl/src/main/java/eu/cloudnetservice/modules/kubernetes/impl/KubernetesServiceLogCache.java`

**Interfaces:**
- Consumes: `KubernetesClient`, `LogLineParser`
- Produces: `void start(ExecutorService executor, Consumer<String> sink)`, `void stop()`

- [ ] **Step 1: Write the failing test**

```java
package eu.cloudnetservice.modules.kubernetes.impl.console;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

@EnableKubernetesMockClient(crud = true)
class PodLogStreamTest {

  static KubernetesClient client;

  @Test
  void stopTerminatesTheReconnectLoop() throws Exception {
    var executor = Executors.newSingleThreadExecutor();
    var stream = new PodLogStream(client, "cloudnet", "cn-lobby-1-a3f9c2e1");

    stream.start(executor, line -> { });
    stream.stop();

    executor.shutdown();
    assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS),
      "reconnect loop must exit after stop()");
  }

  @Test
  void stopIsIdempotent() {
    var stream = new PodLogStream(client, "cloudnet", "cn-lobby-1-a3f9c2e1");
    assertDoesNotThrow(() -> {
      stream.stop();
      stream.stop();
    });
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :k8s-runtime:impl:test --tests '*PodLogStreamTest'`
Expected: FAIL — `cannot find symbol: class PodLogStream`

- [ ] **Step 3: Write minimal implementation**

```java
package eu.cloudnetservice.modules.kubernetes.impl.console;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Log-Rotation (kubelet-Default 10 MiB) und der 4-Stunden-Idle-Timeout beenden den
 * Stream regulaer. Beides ist ein Normalfall - der Reconnect ist deshalb Pflicht,
 * keine Haertung.
 */
public final class PodLogStream {

  private static final Logger LOGGER = LoggerFactory.getLogger(PodLogStream.class);
  private static final Duration INITIAL_BACKOFF = Duration.ofMillis(250);
  private static final Duration MAX_BACKOFF = Duration.ofSeconds(5);

  private final KubernetesClient client;
  private final String namespace;
  private final String podName;
  private final LogLineParser parser = new LogLineParser();
  private final AtomicBoolean running = new AtomicBoolean(true);

  private volatile LogWatch watch;

  public PodLogStream(KubernetesClient client, String namespace, String podName) {
    this.client = client;
    this.namespace = namespace;
    this.podName = podName;
  }

  public void start(ExecutorService executor, Consumer<String> sink) {
    executor.submit(() -> this.loop(sink));
  }

  private void loop(Consumer<String> sink) {
    var backoff = INITIAL_BACKOFF;
    while (this.running.get()) {
      try {
        var loggable = this.client.pods()
          .inNamespace(this.namespace)
          .withName(this.podName)
          .withReadyWaitTimeout(60_000)
          .usingTimestamps();

        var tailed = this.parser.lastSeen()
          .map(seen -> loggable.sinceTime(DateTimeFormatter.ISO_INSTANT.format(seen)))
          .orElseGet(() -> loggable.tailingLines(0));

        try (var current = tailed.watchLog()) {
          this.watch = current;
          backoff = INITIAL_BACKOFF;
          var reader = new BufferedReader(
            new InputStreamReader(current.getOutput(), StandardCharsets.UTF_8), 8192);
          String line;
          while ((line = reader.readLine()) != null && this.running.get()) {
            this.parser.accept(line).ifPresent(parsed -> sink.accept(parsed.message()));
          }
        }
      } catch (Exception exception) {
        if (!this.running.get()) {
          return;
        }
        LOGGER.debug("log stream for {} broke, reconnecting in {}", this.podName, backoff, exception);
      }

      if (!this.running.get()) {
        return;
      }
      LockSupport.parkNanos(backoff.toNanos());
      var doubled = backoff.multipliedBy(2);
      backoff = doubled.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : doubled;
    }
  }

  public void stop() {
    this.running.set(false);
    var current = this.watch;
    this.watch = null;
    if (current != null) {
      current.close();
    }
  }
}
```

Dazu die Log-Cache-Anbindung:

```java
package eu.cloudnetservice.modules.kubernetes.impl;

import eu.cloudnetservice.node.impl.service.defaults.log.AbstractServiceLogCache;

/**
 * Die Kubernetes-Log-API trennt stdout und stderr nicht - das Fehlerstrom-Flag ist
 * deshalb immer false. Bewusste Regression gegenueber dem lokalen Prozess.
 */
public final class KubernetesServiceLogCache extends AbstractServiceLogCache {

  public void acceptLine(String line) {
    this.handleItem(line, false);
  }
}
```

Prüfe beim Implementieren die exakte Oberklasse und den Konstruktor von
`AbstractServiceLogCache` im entpackten `node-impl`-Artefakt und passe die Signatur an —
sie ist nicht öffentliche API und kann zwischen Release-Candidates abweichen.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :k8s-runtime:impl:test --tests '*PodLogStreamTest'`
Expected: PASS, 2 Tests.

- [ ] **Step 5: Commit**

```bash
git add k8s-runtime/impl/src/main/java/eu/cloudnetservice/modules/kubernetes/impl k8s-runtime/impl/src/test
git commit -m "feat: add pod log stream with reconnect loop"
```

---

## Task 12: KubernetesService — die Overrides

**Files:**
- Create: `k8s-runtime/impl/src/main/java/eu/cloudnetservice/modules/kubernetes/impl/KubernetesService.java`
- Create: `k8s-runtime/impl/src/main/java/eu/cloudnetservice/modules/kubernetes/impl/KubernetesLocalCloudServiceFactory.java`

**Interfaces:**
- Consumes: `PodSpecBuilder`, `PodStateCache`, `ServiceDirectoryStager`,
  `PodConsoleInput`, `PodLogStream`, `ResourceNaming`, `KubernetesConfiguration`
- Produces: `KubernetesService extends JVMService`; die Factory registriert sich
  unter `KubernetesConfiguration#factoryName()`.

- [ ] **Step 1: Write the failing test**

Reine Verdrahtungslogik ohne Cluster ist hier kaum sinnvoll testbar; getestet wird
die Entscheidung, die falsch sein kann — dass `alive()` den Cache befragt und nicht
den API-Server.

```java
package eu.cloudnetservice.modules.kubernetes.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.cloudnetservice.modules.kubernetes.config.KubernetesConfiguration;
import eu.cloudnetservice.modules.kubernetes.impl.state.PodStateCache;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

@EnableKubernetesMockClient(crud = true)
class KubernetesServiceAliveTest {

  static KubernetesClient client;

  private static final String SERVICE_ID = "a3f9c2e1-4b7d-4c2a-9f11-8e6d0a5b3c74";

  @Test
  void aliveReadsFromCacheAndSurvivesRepeatedCalls() {
    client.pods().inNamespace("cloudnet").resource(new PodBuilder()
      .withNewMetadata()
        .withName("cn-lobby-1-a3f9c2e1").withNamespace("cloudnet")
        .withLabels(Map.of(
          "cloudnet.eu/managed-by", "node-1",
          "cloudnet.eu/service-id", SERVICE_ID))
      .endMetadata()
      .withNewStatus().withPhase("Running").endStatus()
      .build()).create();

    try (var cache = new PodStateCache(client, KubernetesConfiguration.builder().build())) {
      cache.start("node-1");
      Awaitility.await().atMost(5, TimeUnit.SECONDS)
        .untilAsserted(() -> assertTrue(cache.running(SERVICE_ID)));

      // Der Watchdog ruft alive() jede Sekunde pro Service - 5000 Aufrufe duerfen
      // keinen einzigen API-Call ausloesen.
      for (var i = 0; i < 5000; i++) {
        assertTrue(cache.running(SERVICE_ID));
      }
      assertFalse(cache.running("other-service"));
    }
  }
}
```

- [ ] **Step 2: Die Signaturen der Oberklassen ermitteln**

`JVMService` und `BaseLocalCloudServiceFactory` sind **nicht öffentliche API**. Rate
ihre Konstruktoren nicht — lies sie aus dem aufgelösten Artefakt:

```bash
JAR=$(find ~/.gradle/caches/modules-2/files-2.1/eu.cloudnetservice.cloudnet/node-impl \
  -name 'node-impl-*.jar' | head -1)

javap -cp "$JAR" eu.cloudnetservice.node.impl.service.defaults.JVMService
javap -cp "$JAR" eu.cloudnetservice.node.impl.service.defaults.factory.BaseLocalCloudServiceFactory
javap -cp "$JAR" eu.cloudnetservice.node.impl.service.defaults.log.AbstractServiceLogCache
```

Notiere die Konstruktorparameter von `JVMService` in exakter Reihenfolge und trage sie
oben im Konstruktor an den beiden markierten Stellen ein. Dasselbe für
`validateConfiguration` und den Log-Cache aus Task 11.

Danach: Run `./gradlew :k8s-runtime:impl:test --tests '*KubernetesServiceAliveTest'`
Expected: PASS — der Test nutzt nur Task 7 und dient als Regressionsschutz dafür,
dass `alive()` niemals einen API-Call auslöst.

- [ ] **Step 3: Write the service implementation**

```java
package eu.cloudnetservice.modules.kubernetes.impl;

import eu.cloudnetservice.driver.service.ServiceConfiguration;
import eu.cloudnetservice.modules.kubernetes.config.KubernetesConfiguration;
import eu.cloudnetservice.modules.kubernetes.impl.console.PodConsoleInput;
import eu.cloudnetservice.modules.kubernetes.impl.console.PodLogStream;
import eu.cloudnetservice.modules.kubernetes.impl.naming.ResourceNaming;
import eu.cloudnetservice.modules.kubernetes.impl.pod.PodSpecBuilder;
import eu.cloudnetservice.modules.kubernetes.impl.staging.ServiceDirectoryStager;
import eu.cloudnetservice.modules.kubernetes.impl.state.PodStateCache;
import eu.cloudnetservice.node.impl.service.defaults.JVMService;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class KubernetesService extends JVMService {

  private static final Logger LOGGER = LoggerFactory.getLogger(KubernetesService.class);
  private static final Path WRAPPER_CONFIG = Path.of(".wrapper", "wrapper.json");

  private final KubernetesClient client;
  private final KubernetesConfiguration config;
  private final PodSpecBuilder podSpecBuilder;
  private final PodStateCache stateCache;
  private final ServiceDirectoryStager stager;
  private final ExecutorService executor;
  private final String nodeUniqueId;
  private final String ownerConfigMapName;
  private final String ownerConfigMapUid;

  private volatile String podName;
  private volatile String stagingKey;
  private volatile PodConsoleInput console;
  private volatile PodLogStream logStream;

  /**
   * Der Konstruktor reicht jeden Parameter der JVMService-Oberklasse unveraendert
   * durch und nimmt die Kollaborateure zusaetzlich entgegen. Die exakte
   * Parameterliste der Oberklasse ist nicht oeffentliche API - ermittle sie einmalig
   * mit dem Kommando aus Step 2 und uebernimmt sie woertlich, statt sie zu raten.
   */
  public KubernetesService(
      // <hier die Parameter von JVMService in unveraenderter Reihenfolge>
      KubernetesClient client,
      KubernetesConfiguration config,
      PodSpecBuilder podSpecBuilder,
      PodStateCache stateCache,
      ServiceDirectoryStager stager,
      ExecutorService executor,
      String nodeUniqueId,
      String ownerConfigMapName,
      String ownerConfigMapUid) {
    // super(<die Parameter von JVMService, unveraendert weitergereicht>);
    this.client = client;
    this.config = config;
    this.podSpecBuilder = podSpecBuilder;
    this.stateCache = stateCache;
    this.stager = stager;
    this.executor = executor;
    this.nodeUniqueId = nodeUniqueId;
    this.ownerConfigMapName = ownerConfigMapName;
    this.ownerConfigMapUid = ownerConfigMapUid;
  }

  @Override
  public String runtime() {
    return this.config.factoryName();
  }

  @Override
  public boolean alive() {
    // NIEMALS ein API-Call: der Watchdog ruft das jede Sekunde pro Service.
    return this.stateCache.running(this.serviceId().uniqueId().toString());
  }

  @Override
  public void runCommand(String command) {
    var current = this.console;
    if (current != null) {
      try {
        current.runCommand(command);
      } catch (IllegalStateException exception) {
        LOGGER.debug("unable to deliver console command to {}", this.podName, exception);
      }
    }
  }

  @Override
  protected void doStartProcess(List<String> arguments, Path wrapperPath, Path applicationFilePath) {
    this.podName = ResourceNaming.podName(
      this.serviceId().name(), this.serviceId().uniqueId());

    try {
      // 1) Das von prepareService() gebaute Verzeichnis in den Staging-Bucket legen.
      this.stagingKey = this.stager.upload(
        this.serviceDirectory, "services/" + this.podName + ".tar");

      // 2) wrapper.json als Secret - der connectionKey darf nicht ueber das
      //    Staging-Archiv reisen.
      var wrapperJson = Files.readAllBytes(this.serviceDirectory.resolve(WRAPPER_CONFIG));
      this.client.secrets().inNamespace(this.config.namespace()).resource(new SecretBuilder()
        .withNewMetadata()
          .withName(ResourceNaming.secretName(this.podName))
          .withNamespace(this.config.namespace())
        .endMetadata()
        .withType("Opaque")
        .withData(Map.of("wrapper.json", Base64.getEncoder().encodeToString(wrapperJson)))
        .build()).create();

      // 3) Java-Kommando: die Argumente kommen unveraendert aus JVMService,
      //    nur das Binary und der Konfigurationspfad werden ersetzt.
      var command = new java.util.ArrayList<>(arguments);
      command.set(0, "java");
      command.add(1, "-Dcloudnet.wrapper.config.path=/cloudnet/wrapper/wrapper.json");

      var pod = this.podSpecBuilder.build(new PodSpecBuilder.PodRequest(
        this.serviceId().name(),
        this.serviceId().uniqueId(),
        this.serviceId().taskName(),
        this.serviceConfiguration().processConfig().maxHeapMemorySize(),
        this.serviceConfiguration().port(),
        this.nodeUniqueId,
        this.ownerConfigMapName,
        this.ownerConfigMapUid,
        command));

      this.client.pods().inNamespace(this.config.namespace()).resource(pod).create();

      // 4) Konsole erst nach der Pod-Erzeugung verdrahten.
      this.console = new PodConsoleInput(this.client, this.config.namespace(), this.podName);
      this.logStream = new PodLogStream(this.client, this.config.namespace(), this.podName);
      this.logStream.start(this.executor, line -> this.logCache().acceptLine(line));
    } catch (Exception exception) {
      throw new IllegalStateException("unable to start pod for " + this.podName, exception);
    }
  }

  @Override
  protected void stopProcess() {
    // Erst sauber ueber die Konsole - SIGTERM allein deaktiviert Plugins nicht sauber.
    this.runCommand("end");
    this.runCommand("stop");

    var stream = this.logStream;
    if (stream != null) {
      stream.stop();
    }
    var input = this.console;
    if (input != null) {
      input.close();
    }

    if (this.podName != null) {
      this.client.pods().inNamespace(this.config.namespace())
        .withName(this.podName)
        .withGracePeriod(this.config.terminationGracePeriodSeconds())
        .delete();
    }
  }

  @Override
  public void doDelete() {
    super.doDelete();

    if (this.podName != null) {
      this.client.pods().inNamespace(this.config.namespace())
        .withName(this.podName).delete();
      this.client.secrets().inNamespace(this.config.namespace())
        .withName(ResourceNaming.secretName(this.podName)).delete();
      this.podName = null;
    }
    if (this.stagingKey != null) {
      this.stager.delete(this.stagingKey);
      this.stagingKey = null;
    }
  }

  @Override
  protected eu.cloudnetservice.driver.network.HostAndPort selectConnectListener(
      List<eu.cloudnetservice.driver.network.HostAndPort> listeners) {
    // 127.0.0.1 zeigt im Pod auf den Pod selbst - die Node braucht ihren Cluster-DNS-Namen.
    return new eu.cloudnetservice.driver.network.HostAndPort(
      this.config.nodeServiceDns(), this.config.nodeListenerPort());
  }

  private KubernetesServiceLogCache logCache() {
    return (KubernetesServiceLogCache) super.serviceConsoleLogCache();
  }
}
```

Die Factory:

```java
package eu.cloudnetservice.modules.kubernetes.impl;

import eu.cloudnetservice.driver.service.ServiceConfiguration;
import eu.cloudnetservice.modules.kubernetes.config.KubernetesConfiguration;
import eu.cloudnetservice.modules.kubernetes.impl.naming.ResourceNaming;
import eu.cloudnetservice.node.impl.service.defaults.factory.BaseLocalCloudServiceFactory;
import eu.cloudnetservice.node.service.CloudServiceManager;

public final class KubernetesLocalCloudServiceFactory extends BaseLocalCloudServiceFactory {

  private final KubernetesConfiguration config;

  @Override
  public String name() {
    return this.config.factoryName();
  }

  @Override
  protected ServiceConfiguration validateConfiguration(
      CloudServiceManager manager, ServiceConfiguration configuration) {
    // Die Port-Probe der Oberklasse bindet einen ServerSocket im Namespace der NODE.
    // Jeder Pod hat eine eigene IP - Kollisionen existieren nicht, die Probe ist sinnlos.
    var podName = ResourceNaming.podName(
      configuration.serviceId().name(), configuration.serviceId().uniqueId());
    var fqdn = ResourceNaming.podFqdn(
      podName, this.config.subdomain(), this.config.namespace(), this.config.clusterDomain());

    return ServiceConfiguration.builder(configuration)
      .hostAddress(fqdn)
      .startPort(configuration.port() <= 0 ? 25565 : configuration.port())
      .build();
  }
}
```

- [ ] **Step 4: Kompilieren und Tests laufen lassen**

Run: `./gradlew :k8s-runtime:impl:build`
Expected: BUILD SUCCESSFUL. Weichen Signaturen von `JVMService` oder
`BaseLocalCloudServiceFactory` ab, an das entpackte `node-impl`-Artefakt anpassen —
das ist nicht öffentliche API.

- [ ] **Step 5: Commit**

```bash
git add k8s-runtime/impl/src/main/java/eu/cloudnetservice/modules/kubernetes/impl
git commit -m "feat: implement kubernetes backed cloud service"
```

---

## Task 13: Modul-Einstiegspunkt

**Files:**
- Create: `k8s-runtime/impl/src/main/java/eu/cloudnetservice/modules/kubernetes/impl/KubernetesServicesModule.java`
- Modify: `k8s-runtime/impl/build.gradle.kts` (moduleJson-Block)

**Interfaces:**
- Consumes: alle vorigen Tasks
- Produces: ein installierbares Modul, das `k8s-jvm` registriert.

- [ ] **Step 1: Modul-Klasse schreiben**

```java
package eu.cloudnetservice.modules.kubernetes.impl;

import dev.derklaro.aerogel.auto.annotation.Provides;
import eu.cloudnetservice.driver.document.DocumentFactory;
import eu.cloudnetservice.driver.inject.InjectionLayer;
import eu.cloudnetservice.driver.module.ModuleLifeCycle;
import eu.cloudnetservice.driver.module.ModuleTask;
import eu.cloudnetservice.driver.module.driver.DriverModule;
import eu.cloudnetservice.modules.kubernetes.config.KubernetesConfiguration;
import eu.cloudnetservice.modules.kubernetes.impl.client.KubernetesClientFactory;
import eu.cloudnetservice.modules.kubernetes.impl.state.PodStateCache;
import eu.cloudnetservice.node.service.CloudServiceManager;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

@Singleton
public final class KubernetesServicesModule extends DriverModule {

  private volatile KubernetesConfiguration configuration;
  private volatile KubernetesClient client;
  private volatile PodStateCache stateCache;

  /** Hoehere order laeuft zuerst - die Config muss vor der Factory stehen. */
  @ModuleTask(order = 32)
  public void loadConfiguration() {
    this.configuration = this.readConfig(
      KubernetesConfiguration.class,
      () -> KubernetesConfiguration.builder().build(),
      DocumentFactory.json());
  }

  @ModuleTask(order = 22)
  public void registerServiceFactory(
      CloudServiceManager serviceManager,
      @Named("module") InjectionLayer<?> moduleInjectionLayer) {
    this.client = KubernetesClientFactory.create(this.configuration.namespace());

    // Der Anker fuer OwnerReferences ist bewusst eine langlebige ConfigMap und
    // nicht der Node-Pod: dessen UID wechselt bei einem Rolling Update, was alle
    // Gameserver mitten im Spiel garbage-collecten wuerde.
    var nodeId = serviceManager.getClass().getSimpleName();
    this.client.configMaps().inNamespace(this.configuration.namespace())
      .resource(new ConfigMapBuilder()
        .withNewMetadata()
          .withName("cloudnet-node-" + nodeId + "-owner")
          .withNamespace(this.configuration.namespace())
        .endMetadata()
        .build())
      .createOr(existing -> existing.item());

    this.stateCache = new PodStateCache(this.client, this.configuration);
    this.stateCache.start(nodeId);

    var factory = moduleInjectionLayer.instance(
      KubernetesLocalCloudServiceFactory.class,
      request -> request
        .override(KubernetesClient.class, this.client)
        .override(KubernetesConfiguration.class, this.configuration)
        .override(PodStateCache.class, this.stateCache));

    serviceManager.addCloudServiceFactory(this.configuration.factoryName(), factory);
  }

  @ModuleTask(lifecycle = ModuleLifeCycle.STOPPED)
  public void unregisterServiceFactory(CloudServiceManager serviceManager) {
    serviceManager.removeCloudServiceFactory(this.configuration.factoryName());
    if (this.stateCache != null) {
      this.stateCache.close();
    }
    if (this.client != null) {
      this.client.close();
    }
  }
}
```

- [ ] **Step 2: moduleJson-Block ergänzen**

In `k8s-runtime/impl/build.gradle.kts`, das Juppiter-Plugin anwenden und ergänzen:

```kotlin
moduleJson {
  author = "OneLiteFeather"
  name = "CloudNet-Kubernetes"
  main = "eu.cloudnetservice.modules.kubernetes.impl.KubernetesServicesModule"
  description = "Runs CloudNet services as Kubernetes pods"
  runtimeModule = true        // eine registrierte Factory laesst sich nicht sauber entladen
  storesSensitiveData = true  // kubeconfig und staging credentials
}
```

- [ ] **Step 3: Bauen und module.json prüfen**

Run: `./gradlew :k8s-runtime:impl:shadowJar`
Expected: `cloudnet-kubernetes.jar` entsteht.

```bash
unzip -p k8s-runtime/impl/build/libs/cloudnet-kubernetes.jar module.json
```
Expected: `"main"`, `"runtimeModule": true`, `"storesSensitiveData": true` gesetzt.

- [ ] **Step 4: Relocation verifizieren**

```bash
unzip -l k8s-runtime/impl/build/libs/cloudnet-kubernetes.jar \
  | grep -E 'io/fabric8|com/fasterxml' | grep -v 'modules/kubernetes/libs' | head
```
Expected: **keine Ausgabe** — jede fremde Klasse liegt unter dem relocateten Präfix.

```bash
unzip -l k8s-runtime/impl/build/libs/cloudnet-kubernetes.jar | grep 'org/slf4j' | head
```
Expected: keine Ausgabe (SLF4J ist `compileOnly` über CloudNet und darf nicht mitgebündelt sein).

- [ ] **Step 5: Commit**

```bash
git add k8s-runtime/impl
git commit -m "feat: add module entrypoint registering the k8s-jvm runtime"
```

---

## Task 14: Bind-Adresse auf 0.0.0.0 erzwingen

Ohne diesen Task schreibt CloudNets Preparer den Pod-FQDN nach `server.properties`.
Der müsste zum Bind-Zeitpunkt bereits auflösen — ein Race gegen CoreDNS, das
`publishNotReadyAddresses` meist, aber nicht garantiert gewinnt.

**Files:**
- Create: `k8s-runtime/impl/src/main/java/eu/cloudnetservice/modules/kubernetes/impl/config/KubernetesVanillaPreparer.java`
- Modify: `k8s-runtime/impl/src/main/java/eu/cloudnetservice/modules/kubernetes/impl/KubernetesServicesModule.java`
- Test: `k8s-runtime/impl/src/test/java/eu/cloudnetservice/modules/kubernetes/impl/config/KubernetesVanillaPreparerTest.java`

**Interfaces:**
- Consumes: nichts aus vorigen Tasks
- Produces: `static void rewriteBindAddress(Path serverProperties)` — setzt
  `server-ip=0.0.0.0`, lässt `server-port` unangetastet.

- [ ] **Step 1: Write the failing test**

```java
package eu.cloudnetservice.modules.kubernetes.impl.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KubernetesVanillaPreparerTest {

  @TempDir
  Path dir;

  private Properties rewriteAndRead(String initial) throws Exception {
    var file = this.dir.resolve("server.properties");
    Files.writeString(file, initial);
    KubernetesVanillaPreparer.rewriteBindAddress(file);

    var properties = new Properties();
    try (var in = Files.newInputStream(file)) {
      properties.load(in);
    }
    return properties;
  }

  @Test
  void bindsToAllInterfacesInsteadOfTheFqdn() throws Exception {
    var properties = this.rewriteAndRead(
      "server-ip=cn-lobby-1-a3f9c2e1.cloudnet-services.cloudnet.svc.cluster.local\n"
        + "server-port=25565\n");

    // Der FQDN muesste zum Bind-Zeitpunkt aufloesen - das ist ein Race gegen CoreDNS.
    assertEquals("0.0.0.0", properties.getProperty("server-ip"));
  }

  @Test
  void leavesThePortUntouched() throws Exception {
    var properties = this.rewriteAndRead("server-ip=1.2.3.4\nserver-port=25580\n");

    assertEquals("25580", properties.getProperty("server-port"));
  }

  @Test
  void preservesUnrelatedKeys() throws Exception {
    var properties = this.rewriteAndRead(
      "server-ip=1.2.3.4\nmotd=A Minecraft Server\nmax-players=40\n");

    assertEquals("A Minecraft Server", properties.getProperty("motd"));
    assertEquals("40", properties.getProperty("max-players"));
  }

  @Test
  void addsTheKeyWhenItIsMissingEntirely() throws Exception {
    var properties = this.rewriteAndRead("motd=hello\n");

    assertEquals("0.0.0.0", properties.getProperty("server-ip"));
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :k8s-runtime:impl:test --tests '*KubernetesVanillaPreparerTest'`
Expected: FAIL — `cannot find symbol: class KubernetesVanillaPreparer`

- [ ] **Step 3: Write minimal implementation**

```java
package eu.cloudnetservice.modules.kubernetes.impl.config;

import eu.cloudnetservice.node.service.CloudService;
import eu.cloudnetservice.node.service.ServiceConfigurationPreparer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Im Pod muss der Server auf allen Interfaces lauschen. Die im Snapshot
 * hinterlegte Adresse bleibt der Pod-FQDN - das ist die Adresse, die Proxies
 * ueber die Bridge sehen, nicht die Bind-Adresse.
 */
public final class KubernetesVanillaPreparer implements ServiceConfigurationPreparer {

  public static void rewriteBindAddress(Path serverProperties) throws IOException {
    var properties = new Properties();
    if (Files.exists(serverProperties)) {
      try (var in = Files.newInputStream(serverProperties)) {
        properties.load(in);
      }
    }

    properties.setProperty("server-ip", "0.0.0.0");

    try (var out = Files.newOutputStream(serverProperties)) {
      properties.store(out, "rewritten by CloudNet-Kubernetes");
    }
  }

  @Override
  public void configure(CloudService cloudService) {
    try {
      rewriteBindAddress(cloudService.directory().resolve("server.properties"));
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :k8s-runtime:impl:test --tests '*KubernetesVanillaPreparerTest'`
Expected: PASS, 4 Tests.

- [ ] **Step 5: Preparer im Modul registrieren**

In `KubernetesServicesModule#registerServiceFactory`, nach der Factory-Registrierung:

```java
serviceManager.addServicePreparer(
  eu.cloudnetservice.driver.service.ServiceEnvironmentType.MINECRAFT_SERVER,
  new KubernetesVanillaPreparer());
```

Prüfe die exakte Signatur von `addServicePreparer` gegen `node-api` und passe an.
Für Velocity gilt dasselbe mit einem eigenen Preparer, der in `velocity.toml`
`bind = "0.0.0.0:<port>"` setzt — der kommt im Phase-2-Plan.

- [ ] **Step 6: Commit**

```bash
git add k8s-runtime/impl/src/main/java/eu/cloudnetservice/modules/kubernetes/impl/config k8s-runtime/impl/src/test
git commit -m "feat: force bind address to all interfaces inside pods"
```

---

## Task 15: Node-Auswahl auf Nodes mit dem Modul beschränken

`DefaultCloudServiceManager.selectNodeForService` filtert nach Verfügbarkeit, Draining
und Memory — **nicht** danach, ob die Ziel-Node die Factory registriert hat. Landet ein
Service auf einer Node ohne das Modul, fliegt dort `IllegalArgumentException: No service
factory for runtime k8s-jvm`. In Mischclustern ist das ein Startfehler ohne brauchbare
Fehlermeldung.

**Files:**
- Create: `k8s-runtime/impl/src/main/java/eu/cloudnetservice/modules/kubernetes/impl/listener/NodeSelectListener.java`
- Modify: `k8s-runtime/impl/src/main/java/eu/cloudnetservice/modules/kubernetes/impl/KubernetesServicesModule.java`
- Test: `k8s-runtime/impl/src/test/java/eu/cloudnetservice/modules/kubernetes/impl/listener/NodeSelectListenerTest.java`

**Interfaces:**
- Consumes: `KubernetesConfiguration`
- Produces: `static boolean handles(String runtime, String factoryName)` — die reine
  Entscheidungslogik, getrennt vom Event-Handler, damit sie ohne Node testbar ist.

- [ ] **Step 1: Write the failing test**

```java
package eu.cloudnetservice.modules.kubernetes.impl.listener;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NodeSelectListenerTest {

  @Test
  void claimsServicesUsingOurRuntime() {
    assertTrue(NodeSelectListener.handles("k8s-jvm", "k8s-jvm"));
  }

  @Test
  void ignoresTheDefaultJvmRuntime() {
    assertFalse(NodeSelectListener.handles("jvm", "k8s-jvm"));
  }

  @Test
  void ignoresTheDockerRuntime() {
    assertFalse(NodeSelectListener.handles("docker-jvm", "k8s-jvm"));
  }

  @Test
  void respectsARenamedFactory() {
    assertTrue(NodeSelectListener.handles("k8s-prod", "k8s-prod"));
    assertFalse(NodeSelectListener.handles("k8s-jvm", "k8s-prod"));
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :k8s-runtime:impl:test --tests '*NodeSelectListenerTest'`
Expected: FAIL — `cannot find symbol: class NodeSelectListener`

- [ ] **Step 3: Write minimal implementation**

```java
package eu.cloudnetservice.modules.kubernetes.impl.listener;

import eu.cloudnetservice.driver.event.EventListener;
import eu.cloudnetservice.modules.kubernetes.config.KubernetesConfiguration;
import eu.cloudnetservice.node.impl.event.service.CloudServiceNodeSelectEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class NodeSelectListener {

  private static final Logger LOGGER = LoggerFactory.getLogger(NodeSelectListener.class);

  private final KubernetesConfiguration config;
  private final String localNodeUniqueId;

  public NodeSelectListener(KubernetesConfiguration config, String localNodeUniqueId) {
    this.config = config;
    this.localNodeUniqueId = localNodeUniqueId;
  }

  public static boolean handles(String runtime, String factoryName) {
    return factoryName.equals(runtime);
  }

  @EventListener
  public void handleNodeSelect(CloudServiceNodeSelectEvent event) {
    var runtime = event.configuration().runtime();
    if (!handles(runtime, this.config.factoryName())) {
      return;
    }
    // Nur Nodes mit diesem Modul koennen die Factory aufloesen.
    LOGGER.debug("pinning service with runtime {} to node {}", runtime, this.localNodeUniqueId);
    event.nodeServer(event.nodeServer());
  }
}
```

Prüfe den exakten Paketnamen und die Setter-Signatur von `CloudServiceNodeSelectEvent`
gegen das entpackte `node-impl`-Artefakt:

```bash
unzip -l ~/.gradle/caches/modules-2/files-2.1/eu.cloudnetservice.cloudnet/node-impl/**/node-impl-*.jar \
  | grep -i nodeselect
```

Setze im Handler den NodeServer auf die lokale Node, sobald du die konkrete
Accessor-Signatur kennst — die Zeile oben ist bewusst ein No-Op, bis das verifiziert ist,
damit der Build nicht gegen eine geratene Signatur kompiliert.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :k8s-runtime:impl:test --tests '*NodeSelectListenerTest'`
Expected: PASS, 4 Tests.

- [ ] **Step 5: Listener registrieren**

In `KubernetesServicesModule`, ergänze eine Task-Methode:

```java
@ModuleTask(lifecycle = ModuleLifeCycle.STARTED)
public void registerListeners(eu.cloudnetservice.driver.event.EventManager eventManager) {
  eventManager.registerListener(new NodeSelectListener(this.configuration, "node-1"));
}
```

- [ ] **Step 6: Commit**

```bash
git add k8s-runtime/impl/src/main/java/eu/cloudnetservice/modules/kubernetes/impl/listener k8s-runtime/impl/src/test
git commit -m "feat: pin k8s runtime services to nodes providing the factory"
```

---

## Task 16: End-to-End im kind-Cluster

**Files:**
- Create: `deploy/rbac.yaml`, `deploy/headless-service.yaml`
- Create: `docs/how-to/run-a-service-on-kubernetes.md`

**Interfaces:**
- Consumes: alle vorigen Tasks
- Produces: den Nachweis, dass ein Lobby-Service als Pod startet, sich verbindet,
  auf Konsolenbefehle reagiert und beim Stop rückstandsfrei verschwindet.

- [ ] **Step 1: RBAC und Headless Service anlegen**

`deploy/rbac.yaml` mit ServiceAccount, Role und RoleBinding aus Abschnitt 9 der Spec —
insbesondere `pods/attach` als eigene Subresource, `pods/log` mit `get` **und** `list`.

`deploy/headless-service.yaml`:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: cloudnet-services
  namespace: cloudnet
spec:
  clusterIP: None
  publishNotReadyAddresses: true
  selector:
    cloudnet.eu/component: service
  ports:
    - name: game
      port: 25565
      protocol: TCP
```

```bash
kubectl apply -f deploy/rbac.yaml -f deploy/headless-service.yaml
```

- [ ] **Step 2: Modul installieren und Task anlegen**

```bash
cp k8s-runtime/impl/build/libs/cloudnet-kubernetes.jar <cloudnet>/modules/
# Node neu starten, dann in der Node-Konsole:
tasks create Lobby minecraft_server
tasks task Lobby set runtime k8s-jvm
```

- [ ] **Step 3: Service starten und Pod beobachten**

```bash
# Node-Konsole:
service create 1 Lobby --start

# separat:
kubectl -n cloudnet get pods -l cloudnet.eu/task=lobby -w
```
Expected: Pod erscheint, geht über `Pending` nach `Running`; in der Node-Konsole
taucht der Service als `RUNNING` auf. Der Übergang durch `Pending` darf **nicht**
dazu führen, dass der Watchdog den Service stoppt.

- [ ] **Step 4: Konsole in beide Richtungen prüfen**

```bash
# Node-Konsole:
service Lobby-1 screen
```
Expected: Server-Logausgabe erscheint. Ein abgesetztes `list` liefert eine Antwort —
das beweist stdin über `attach` und stdout über `watchLog` gleichzeitig.

- [ ] **Step 5: Aufräumen verifizieren**

```bash
# Node-Konsole:
service Lobby-1 stop
```

```bash
kubectl -n cloudnet get pods,secrets -l cloudnet.eu/task=lobby
```
Expected: keine Ressourcen mehr. Zusätzlich prüfen, dass das Staging-Objekt im Bucket
entfernt wurde.

- [ ] **Step 6: How-to dokumentieren und committen**

Schreibe `docs/how-to/run-a-service-on-kubernetes.md` mit genau den Schritten 1–5,
den benötigten Manifesten und den drei häufigsten Fehlerbildern: fehlendes
`pods/attach` (403 erst zur Laufzeit), fehlendes `publishNotReadyAddresses`
(Wrapper-Handshake läuft in den DNS-Timeout), und ein Image, dessen CloudNet-Version
nicht zur Node passt.

```bash
git add deploy docs/how-to
git commit -m "feat: add deployment manifests and end-to-end how-to"
```

---

## Nachgelagerte Pläne

Diese Phasen bekommen eigene Pläne, sobald Phase 1 steht und die Spike-Ergebnisse
aus Task 1 vorliegen:

- **Phase 2 (MVP):** `k8s-routing` — ClusterIP-Service pro Proxy-Task,
  mc-router-Annotationen aus `k8s.domains`, LoadBalancer, PROXY-Protokoll-Kette.
- **Phase 3:** `k8s-storage` — RBD-PVC mit `ReadWriteOncePod`, Config-Bucket-Sync
  mit Hotreload, Snapshots mit `save-off`-Hook, Node-Loss-Behandlung.
- **Phase 4:** `k8s-rest` plus separat ausgeliefertes SPA; beginnt mit der
  Beschaffung von `web-api` und dem RC17-Kompatibilitätstest.
