# CloudNet als Kubernetes-Operator — Design

**Datum:** 2026-08-28
**Status:** Entwurf zur Freigabe
**Basis:** CloudNet `4.0.0-RC17-SNAPSHOT`, Kubernetes v1.36, Rook-Ceph

## Ziel

CloudNet v4 soll Services als Kubernetes-Pods starten statt als lokale JVM-Prozesse
oder Docker-Container. Die Runtime muss eigenständig lauffähig sein — ohne Storage-
Modul, ohne mc-router und ohne das REST-Modul. Storage, Routing und Administration
kommen als getrennte, optionale Module obendrauf.

## Rahmenbedingungen

| Frage | Entscheidung |
|---|---|
| Node-Platz | In-Cluster (ServiceAccount) **und** extern (Kubeconfig), Auto-Detect |
| Code-Heimat | Eigenes OLF-Monorepo, externe Module gegen publizierte CloudNet-Artefakte |
| Cluster | Rook-Ceph vorhanden, TCP-LoadBalancer vorhanden |
| MVP (Phase 2) | Proxy + Backend, von außen über eine Domain spielbar |

Verifiziert: `node-api` und `node-impl` tragen beide `id("cloudnet-publish")`
(`node/api/build.gradle.kts:4`, `node/impl/build.gradle.kts:14`) — externe Module
sind ohne Fork baubar. Cygnus baut bereits gegen
`eu.cloudnetservice.cloudnet:bom:4.0.0-RC16` über `repository.derklaro.dev`.

---

## 1. Modulfamilie

Ein Gradle-Monorepo `cloudnet-kubernetes`, vier Module im `api`/`impl`-Split nach
CloudNet-Vorbild.

| Modul | `runtime`-Wert | Abhängig von | Zweck |
|---|---|---|---|
| `k8s-runtime` | `k8s-jvm` | nur CloudNet | Pods, Konsole, Informer, Adressierung |
| `k8s-routing` | — | k8s-runtime | ClusterIP-Service + mc-router-Annotationen |
| `k8s-storage` | — | k8s-runtime | PVC-Lifecycle, Config-Bucket-Sync, Snapshots |
| `k8s-rest` | — | k8s-runtime, *soft* CloudNet-Rest | REST-Endpoints, deaktiviert sich still |

Jedes `impl` deklariert:

```kotlin
moduleJson {
  main = "eu.cloudnetservice.modules.kubernetes.impl.KubernetesServicesModule"
  runtimeModule = true        // nicht reload-/unloadbar: eine registrierte Factory
                              // lässt sich nicht sauber aus laufenden Services ziehen
  storesSensitiveData = true  // Kubeconfig / SA-Token nicht in Cluster-Sync und Report
}
```

### Warum das Routing kein Teil des Kerns ist

Die Anforderung "Runtime muss ohne mc-router laufen" wird strukturell erfüllt, nicht
per Konfigurationsschalter. `k8s-runtime` kennt weder mc-router noch Ceph. Das
Routing-Modul hängt sich über `CloudServicePostLifecycleEvent` ein — dieselbe Naht,
die das Cloudflare-Modul für SRV-Records nutzt
(`modules/cloudflare/impl/.../CloudflareServiceStateListener.java:54-95`).

**Wichtig:** Nicht `CloudServiceLifecycleChangeEvent` verwenden — das feuert
node-seitig nur für *fremde* Services
(`ServiceChannelMessageListener.java:113-119`).

---

## 2. Der Erweiterungspunkt

Das SPI ist minimal — `node/api/.../service/LocalCloudServiceFactory.java:23-26`:

```java
public interface LocalCloudServiceFactory extends Named {
  CloudService createCloudService(CloudServiceManager manager, ServiceConfiguration configuration);
}
```

Der Rückgabewert wird jedoch hart auf `InternalCloudService` gecastet
(`DefaultCloudServiceManager.java:592`), und `CloudService` erbt ~40 Methoden von
`SpecificCloudServiceProvider`. Realistisch ist nur die Ableitungskette, die auch
das Docker-Modul nutzt:

```
KubernetesService extends JVMService extends AbstractService implements InternalCloudService
```

Das erzwingt `compileOnlyApi(projects.node.nodeImpl)` — **nicht-öffentliche,
versionsinstabile API**. Das ist das größte Dauerrisiko des Projekts und der Grund,
warum jedes Design, das weniger davon anfasst, dem vorzuziehen ist.

### Zu überschreibende Methoden

Dieselben fünf wie im Docker-Modul, plus zwei:

| Methode | Zweck |
|---|---|
| `doStartProcess(args, wrapperPath, appPath)` | Pod erzeugen und starten (Hook aus `JVMService:190`) |
| `stopProcess()` | Konsolen-Stop, dann Pod-Delete mit Grace-Period |
| `alive()` | **aus dem Informer-Cache**, nie per API-Call |
| `doDelete()` | `super.doDelete()`, dann Pod entfernen |
| `selectConnectListener(List)` | Node-Service-DNS statt `127.0.0.1` |
| `runCommand(String)` | stdin über lazy `attach()` |
| `validateConfiguration(...)` | Port-Probe ersetzen, Pod-FQDN als `hostAddress` |

### Registrierung

```java
@ModuleTask(order = 22)   // HÖHERE order läuft ZUERST (DefaultModuleWrapper.java:64-65)
public void registerServiceFactory(CloudServiceManager serviceManager,
                                   @Named("module") InjectionLayer<?> layer) {
  serviceManager.addCloudServiceFactory("k8s-jvm", factory);
}

@ModuleTask(lifecycle = ModuleLifeCycle.STOPPED)
public void unregister(CloudServiceManager m) { m.removeCloudServiceFactory("k8s-jvm"); }
```

---

## 3. Kubernetes-Client

**fabric8 `kubernetes-client` 7.8.0 mit `kubernetes-httpclient-jdk`, Vert.x
ausgeschlossen, relocated in den Shadow-Jar.**

Der offizielle `io.kubernetes:client-java` scheidet aus: er zieht Gson (kollidiert
direkt mit CloudNets Gson 2.13.2), dazu Protobuf, das AWS-STS-SDK, Google-OAuth und
einen eingebetteten Prometheus-HTTP-Server. JOSDK ist eine Schicht *auf* fabric8 und
damit die falsche Ebene, wenn CloudNet der State-Owner ist.

`kubernetes-httpclient-jdk` hat als einzige Runtime-Abhängigkeit
`kubernetes-client-api` und nutzt `java.net.http`. `JdkWebSocketImpl` ist enthalten,
also funktionieren `attach`, `exec`, `watch` und Log-Streaming ohne Vert.x, OkHttp
oder Netty 4.

### Shading statt moduleLibrary

Das Docker-Modul nutzt `moduleLibrary(...)`. Wir weichen bewusst ab:
`ModuleURLClassLoader.loadClass` iteriert bei einem Miss über **alle** registrierten
Modul-Loader (`driver/impl/.../ModuleURLClassLoader.java:129-154`) — Jackson,
BouncyCastle und snakeyaml-engine würden in andere Module lecken.

```kotlin
tasks.shadowJar {
  mergeServiceFiles()   // PFLICHT: HttpClient$Factory + KubernetesResource
  val libs = "eu.cloudnetservice.modules.kubernetes.libs"
  relocate("com.fasterxml.jackson", "$libs.jackson")
  relocate("org.snakeyaml",         "$libs.snakeyaml")
  relocate("org.bouncycastle",      "$libs.bouncycastle")
  relocate("io.fabric8",            "$libs.fabric8")
  // org.slf4j NICHT relocaten — soll auf CloudNets Logging landen
}
```

### TCCL-Falle

`KubernetesClientBuilder` lädt `KubernetesClientImpl` über
`Thread.currentThread().getContextClassLoader()`. Im CloudNet-Modul-Thread ist der
TCCL nicht der `ModuleURLClassLoader`. Die Factory deshalb **immer** explizit setzen
und den ServiceLoader umgehen:

```java
new KubernetesClientBuilder()
    .withHttpClientFactory(new JdkHttpClientFactory())
    .withConfig(new ConfigBuilder().build())   // In-Cluster-Autodetect, sonst Kubeconfig
    .build();
```

Das erfüllt zugleich die Anforderung "In-Cluster und extern": `ConfigBuilder`
findet `/var/run/secrets/kubernetes.io/serviceaccount` selbst und fällt sonst auf
`KUBECONFIG` bzw. `~/.kube/config` zurück.

---

## 4. Workload-Form

**Bare Pod, `restartPolicy: Never`, ein Pod pro CloudNet-Service. Keine CRDs.**

Deployment und ReplicaSet scheiden aus, weil sie Pods als fungibel behandeln —
sie skalieren *irgendeinen* herunter, nicht den leeren. Job bringt einen zweiten
Controller in den Lifecycle. StatefulSet erzwingt Ordinale und ist zusätzlich in
einem entscheidenden Fall defekt (siehe §8). Agones 1.60 erzeugt aus demselben
Grund Bare Pods.

`restartPolicy: Never` ist nicht kosmetisch: bei `Always` startet der Container mit
demselben `connectionKey` neu, die Node kennt den Service aber bereits als beendet
— der Handshake schlägt fehl und der Pod läuft in `CrashLoopBackOff`.

### Warum keine CRDs

Die Frage ist nicht "CRD ja/nein", sondern **wo der maßgebliche State liegt**. Bei
CloudNet liegt er im `DefaultCloudServiceManager` samt Cluster-Sync. Ein
CRD-Operator wäre eine zweite, konkurrierende Wahrheit: Node stürzt ab, Operator
sieht eine CR ohne Pod, erzeugt einen — Node kommt zurück, kennt den Service nicht,
Zombie.

Der `kubectl`-Sichtbarkeitsvorteil wird zu ~90 % durch Labels, Annotationen und
Events erschlagen. Nebeneffekt der CRD-Freiheit: **alles ist namespaced, es wird
keine einzige ClusterRole gebraucht.**

---

## 5. Adressierung

Das schwerste Timing-Problem: `ServiceInfoSnapshot#address()` wird in
`prepareService()` fixiert — **bevor der Pod existiert**. Eine Pod-IP gibt es zu
diesem Zeitpunkt nicht.

**Lösung: `spec.hostname` + `spec.subdomain` gegen genau einen Headless Service.**

Kubernetes vergibt einem Pod mit beiden Feldern einen eigenen A-Record
`<hostname>.<subdomain>.<namespace>.svc.cluster.local`, sofern im Namespace ein
Headless Service mit dem Subdomain-Namen existiert. Die Adresse ist damit
**deterministisch aus der Service-ID ableitbar, bevor der Pod erzeugt wird**.

```yaml
# EINMALIG, nicht pro Service
apiVersion: v1
kind: Service
metadata: { name: cloudnet-services, namespace: cloudnet }
spec:
  clusterIP: None
  publishNotReadyAddresses: true   # PFLICHT: sonst raced der Wrapper-Handshake
                                   # gegen CoreDNS
  selector: { cloudnet.eu/component: service }
  ports: [{ name: game, port: 25565, protocol: TCP }]
```

Verworfen: ein Service pro Pod (Performance-Klippe ab ~5000 Services, ~50 000
iptables-Regeln, `iptables-restore` 5–15 s CPU pro Node), LoadBalancer pro Pod
(30–120 s Provisioning gegen ein 30-s-Timeout).

### Bridge bleibt unverändert

`VelocityBridgeManagement.java:100` liest ausschließlich
`ServiceInfoSnapshot#address()` und baut daraus ein `InetSocketAddress`. Identisch
in `BungeeCordHelper` und `WaterDogPEBridgeManagement`. Ist `address()` der FQDN
und läuft der Proxy im Cluster, sind **null Änderungen an den Bridge-Modulen**
nötig. Das ist die sauberste Integrationsnaht des Entwurfs.

Im Proxy-Pod nötig, weil Velocity `InetSocketAddress` cacht:

```yaml
dnsConfig:
  options: [{ name: ndots, value: "2" }, { name: timeout, value: "1" }]
```
plus `-Dsun.net.inetaddr.ttl=5`.

### Bind-Adresse

`VanillaServiceConfigurationPreparer` schreibt `hostAddress` direkt nach
`server.properties`. Stünde dort der FQDN, müsste er zum Bind-Zeitpunkt bereits
auflösen — ein Race gegen CoreDNS. Deshalb eigene K8s-Preparer registrieren
(`CloudServiceManager#addServicePreparer`), die `server-ip=0.0.0.0` erzwingen,
während `hostAddress` im Snapshot der FQDN bleibt.

### Port-Vergabe

`BaseLocalCloudServiceFactory.findFreeServicePort` (`:100-131`) testet Ports per
echtem `ServerSocket.bind` im Namespace der **Node** — in K8s bedeutungslos, weil
jeder Pod eine eigene IP hat. `validateConfiguration` überschreiben, Port fix auf
den Environment-Default (25565).

---

## 6. Service-Verzeichnis: Staging über Object Storage

Das Docker-Modul löst das mit `bindFromPath()`, das Host-Pfad auf **identischen**
Container-Pfad mountet (`DockerizedService.java:401-403`). Nur deshalb funktioniert
der Classpath aus absoluten Node-Pfaden (`launcher/libs`,
`temp/caches/wrapper.jar`). In Kubernetes ist das prinzipiell nicht reproduzierbar.

**Gewählter Ansatz:** Die Node ruft `prepareService()` **unverändert** auf lokaler
Disk auf, packt das Ergebnis als tar und legt es in einen Ceph-RGW-Bucket; ein
Init-Container zieht und entpackt es.

Begründung: Es ist der Ansatz, der am wenigsten von `node-impl` anfasst — dem
versionsinstabilen Teil (§2). `prepareService()`, `prepareApplicationFile()` und die
gesamte Argument-Konstruktion in `JVMService.startProcess()` bleiben unangetastet.
Er funktioniert außerdem sowohl mit In-Cluster- als auch mit externer Node und macht
die Node nicht zum IO-Single-Point-of-Failure.

Verworfen:
- **Geteiltes CephFS (RWX):** kleinster Code-Eingriff, aber zwingt die Node in den
  Cluster (bricht die Extern-Anforderung), macht sie zum IO-SPOF und legt
  MDS-Metadatenlatenz auf den Save-Pfad.
- **Pod-native Vorbereitung:** sauberste Zielarchitektur, erfordert aber, die
  ConfigPreparer-Logik (NightConfig auf `velocity.toml`, `server.properties`) im Pod
  nachzubauen, und `prepareApplicationFile()` könnte die Main-Class nicht mehr
  ermitteln.

### wrapper.json als Secret

Der `connectionKey` (`StringUtil.generateRandomString(64)`,
`AbstractService.java:151`) existiert ausschließlich als Datei im Service-Verzeichnis.
Der Wrapper liest sie aus `-Dcloudnet.wrapper.config.path`, Default
`.wrapper/wrapper.json` (`DocumentWrapperConfiguration.java:37-38`).

**Diese überschreibbare Property ist der zentrale Design-Hebel:** `wrapper.json`
wird als Kubernetes-Secret gerendert, mit `defaultMode: 0400` gemountet und die
Property gesetzt. Damit braucht die Authentifizierung kein geteiltes Dateisystem.

Das Secret enthält den Connection-Key und trägt eine OwnerReference auf denselben
Anker wie der Pod (§8), damit es mitgeräumt wird.

### Launcher-Libs und Wrapper-Jar

`-cp` verweist auf `launcher/libs` und `temp/caches/wrapper.jar` als absolute
Node-Pfade. Beide gehören ins Basis-Image, **versionsgleich zur Node**. Das
Image-Tag muss deshalb an die CloudNet-Version gekoppelt sein; ein Versionsversatz
zwischen Node und Image ist ein Startfehler, der erst im Wrapper-Handshake auffällt.

---

## 7. Konsole und Logs

Zwei getrennte Kanäle, weil keiner allein reicht.

**stdin über `attach()`, lazy.** `exec()` ist falsch — es startet einen *neuen*
Prozess, dessen stdin nicht die von PID 1 ist; ein `stop` käme nie am Server an.
Der Attach-Stream wird erst beim ersten Konsolenbefehl geöffnet und nach einem
Bruch neu aufgebaut. Damit ist der 4-Stunden-`streamingConnectionIdleTimeout`
irrelevant, ohne Keepalive-Bytes zu senden, die der Server als leere Kommandos sähe.

**stdout über `watchLog().usingTimestamps()` mit Reconnect-Schleife.** Nur die
Log-API kennt `sinceTime` und kann lückenarm wiederaufsetzen. Log-Rotation (Default
10 MiB) und der 4-Stunden-Timeout sind **Normalfälle, keine Fehler** — die
Reconnect-Schleife ist Pflicht, nicht Härtung. Weil `sinceTime` nur
Sekundenauflösung hat, müssen Zeilen mit `ts <= lastSeen` verworfen werden.

PodSpec-Pflichtfelder:

```yaml
stdin: true
stdinOnce: false   # KRITISCH: bei true schließt die Runtime stdin beim ersten
                   # Detach — die Konsole ist dann für immer tot
tty: false         # true multiplext stdout/stderr und übersetzt CRLF
```

**Bewusste Regression:** Die Kubernetes-Log-API trennt stdout und stderr nicht. Das
`stderr`-Flag in `handleItem` ist immer `false`. Bei Minecraft-Servern loggt
praktisch alles über Log4j nach stdout; der Verlust ist vertretbar, gehört aber ins
Changelog.

Auf den Gameserver-Nodepools: `containerLogMaxSize: 50Mi` (Default 10 MiB rotiert
bei Minecraft-Logspam zu oft).

---

## 8. Lifecycle-Robustheit

### Der Watchdog erzwingt einen Informer

`DefaultCloudServiceManager.java:174-185` ruft `alive()` **jede Sekunde für jeden
lokalen Service**. Ein ungecachter API-Call pro Service pro Sekunde überlastet den
API-Server. `alive()` wird deshalb aus einem `SharedIndexInformer<Pod>` mit
Label-Selektor `cloudnet.eu/managed-by=<nodeId>` bedient — O(1), kein Netzwerk.

Zweite Falle: Kubernetes kennt die Phase `Pending`, Docker nicht. Ein Pod, der noch
scheduled wird, würde vom Watchdog als tot gewertet und gestoppt. **Ein
Grace-Fenster nach dem Start ist zwingend.**

### Shutdown

`SIGTERM` allein speichert bei Paper zwar die Welt, deaktiviert Plugins aber nicht
sauber (PaperMC#3062, #5270). CloudNets Reihenfolge bleibt deshalb richtig:
`runCommand("end")` / `runCommand("stop")` über stdin, danach `delete()` mit
Grace-Period als Backstop.

`terminationGracePeriodSeconds = processTerminationTimeoutSeconds + 30` (typisch
120). Kein `preStop`-Hook — er liefe zusätzlich zum bereits abgesetzten `/stop` und
verbrauchte die Grace-Period doppelt. **Keine Finalizer** — sie sind die häufigste
Ursache für `Terminating`-Zombies.

### Verwaiste Pods — vier Ebenen

1. **OwnerReference auf eine langlebige ConfigMap `cloudnet-node-<id>-owner`,
   nicht auf den Node-Pod.** Wird der Node-Pod bei einem Rolling Update ersetzt,
   ändert sich seine UID — und der Garbage Collector löscht **alle Gameserver
   mitten im Spiel**. Die ConfigMap überlebt Restarts und wird nur entfernt, wenn
   die Node dauerhaft aus dem Cluster geht.
2. **`activeDeadlineSeconds: 86400`** als kubelet-lokaler Backstop. Wirkt auch dann,
   wenn Node, Modul und Informer gleichzeitig tot sind.
3. **Informer-Resync beim Node-Start** ist die Rekonziliation: Pods mit unserem
   `managed-by`-Label ohne bekannten Service werden gelöscht. Adoption ist
   unmöglich — der `connectionKey` ist prozesslokal und die Verbindung weg.
4. **`coordination.k8s.io` Lease pro Node** gegen Split-Brain bei mehreren Nodes im
   selben Namespace.

### Node-Ausfall ist ein Modul-Thema

Bei einem hart ausgefallenen Node greifen Kubernetes-Defaults erst nach ~50 s
(`NotReady`), ~350 s (`deletionTimestamp`) und weiteren 6 Minuten
(`ReconcilerMaxWaitForUnmountDuration` — **hartcodiert, nicht konfigurierbar**).
Ergebnis: ~11–12 Minuten bei einem Deployment.

Bei einem **StatefulSet wird überhaupt kein Ersatz-Pod erzeugt**, weil der Name noch
vom `Terminating`-Pod belegt ist — der Server käme ohne manuelles `--force` nie
zurück. Das ist der zweite, härtere Grund gegen StatefulSets.

Da CloudNet die Lifecycle-Hoheit ohnehin besitzt, behandelt **das Modul** den
Node-Verlust selbst: erkennen, `node.kubernetes.io/out-of-service`-Taint setzen,
Service neu platzieren.

**Runbook-Pflicht:** Mounts und Device-Mappings eines ausgefallenen Nodes überleben,
bis dieser einen **vollständigen Power-Cycle** hatte. Der Taint darf erst danach
entfernt werden — sonst drohen Dateninkonsistenz und Korruption.

### Node-Auswahl kennt `runtime` nicht

`DefaultCloudServiceManager.selectNodeForService` (`:462-503`) filtert nur nach
Verfügbarkeit, Draining, `allowedNodes` und Memory — nicht danach, ob die Ziel-Node
die Factory registriert hat. Landet ein Service auf einer Node ohne das Modul,
fliegt dort `IllegalArgumentException: No service factory for runtime k8s-jvm`.

Gegenmittel: `CloudServiceNodeSelectEvent` abonnieren (cancelbar, `nodeServer`
setzbar) und die Node erzwingen. Zusätzlich ist `canStartNow()`
(`AbstractService.java:766-771`), das gegen das Memory-Budget der Node prüft,
inhaltlich falsch — der Heap liegt nicht auf der Node.

---

## 9. Ressourcen und RBAC

`-Xmx`/`-Xms` werden von `JVMService` hart gesetzt und überschreiben
`MaxRAMPercentage` immer. Das ist richtig so — `maxHeapMemory` ist ein bewusst
gewählter Ops-Wert.

Der typische Fehler ist **Limit = Heap**. Non-Heap (Metaspace, Thread-Stacks,
Direct Buffers, Netty) braucht 20–35 % Reserve:

```
memoryLimit = maxHeapMemory * 1.35, mindestens +512 MiB
4096 MiB Heap  ->  5632Mi Limit
```

**Memory `request == limit`, CPU nur `request`, kein CPU-Limit.** Ein CPU-Limit
erzeugt CFS-Throttling — exakt die Mikro-Stutter, die Spieler als Lag wahrnehmen,
und ein Chunk-Generierungs-Spike *muss* kurzzeitig mehr ziehen dürfen. Formal ist
das Burstable, Memory verhält sich faktisch wie Guaranteed.

Weiteres: `automountServiceAccountToken: false` (der Gameserver braucht keinen
API-Zugriff), `enableServiceLinks: false` (sonst hunderte Legacy-Env-Vars),
`imagePullPolicy: IfNotPresent` (`Always` frisst das 30-s-Budget),
Topology-Spread nur `ScheduleAnyway` (`DoNotSchedule` macht Pods unschedulebar und
lässt das Wrapper-Timeout verfallen), keine Liveness-/Readiness-Probe (CloudNets
Watchdog ist die Health-Quelle).

### RBAC — namespaced, keine ClusterRole

```yaml
rules:
  - apiGroups: [""]
    resources: ["pods"]
    verbs: ["get","list","watch","create","delete","deletecollection"]
  - apiGroups: [""]
    resources: ["pods/log"]
    verbs: ["get","list"]          # beide nötig, sonst schlägt watchLog fehl
  - apiGroups: [""]
    resources: ["pods/attach"]
    verbs: ["create","get"]        # EIGENE Subresource — exec deckt sie NICHT ab
  - apiGroups: [""]
    resources: ["services"]
    verbs: ["get","list","watch","create"]
  - apiGroups: [""]
    resources: ["configmaps"]
    verbs: ["get","list","watch","create","update","delete"]
  - apiGroups: [""]
    resources: ["secrets"]
    verbs: ["get","list","watch","create","delete"]
  - apiGroups: [""]
    resources: ["events"]
    verbs: ["create","patch"]
  - apiGroups: ["coordination.k8s.io"]
    resources: ["leases"]
    verbs: ["get","list","watch","create","update"]
```

`pods/exec` kommt erst in Phase 4 dazu, wenn die WebUI Dateien im laufenden Pod
bearbeitet. Auf `pods/portforward` wird dauerhaft verzichtet.

---

## 10. Storage

### Weltdaten auf RBD, nicht CephFS, nicht S3

Anvil-`.mca`-Dateien werden über `RandomAccessFile` **in-place an beliebigen
Offsets** in 4096-Byte-Sektoren geschrieben, bei jedem Autosave, vom Main-Thread,
von genau einem Writer. Das ist ein Lehrbuchfall für ein Blockdevice. CephFS würde
MDS-Roundtrips auf den Save-Pfad legen.

**S3 als Mount ist strukturell ausgeschlossen:** S3 kennt für normale Buckets
keinen partiellen Objekt-Update. Ein 4-KiB-Rewrite in einer 8-MB-Regiondatei kostet
je nach Mounter 5–8 MB Re-Upload (~1000–2000× Schreibverstärkung) oder scheitert
hart. Dazu fehlt `flock`/`fcntl` — SQLite, also praktisch jedes Plugin mit
Datenbank, ist damit unmöglich. GeeseFS puffert Writes im Speicher und meldet
fehlgeschlagene Schreibvorgänge **nicht an den Writer**.

Ebenfalls verworfen: RWX auf RBD (ceph-csi lehnt es außer für raw-block ab und
deaktiviert dann bewusst die In-Use-Prüfung).

### `ReadWriteOncePod`, nicht `ReadWriteOnce`

Die Kubernetes-Dokumentation ist eindeutig: *"Volume access modes do not enforce
write protection once the storage has been mounted."* RWO, ROX und RWX sind
Scheduling-Hinweise, **keine Garantien**. Nur `ReadWriteOncePod` wird tatsächlich
erzwungen. `session.lock` schützt nur gegen kooperative Prozesse.

Für Weltdaten ist das der Unterschied zwischen einer Konvention und einer
erzwungenen Invariante — und ein Doppelmount bedeutet irreparable Korruption.

### PVC-Identität

```
staticService == true   ->  PVC (cloudnet-persistent, Retain, RWOP)
staticService == false  ->  emptyDir
```

Kein neues Konzept — das ist exakt die vorhandene Unterscheidung zwischen
`local/services/<name>` und `temp/services/<name>_<uuid>`
(`AbstractService.resolveServicePath`).

Der PVC-Name wird **ausschließlich aus `serviceId.name()`** gebildet:
`cn-<name>-data`. Die `uniqueId` darf niemals einfließen — sonst bekäme Survival-1
bei jedem Start ein frisches, leeres Volume. Bei Überlänge auf 63 Zeichen kürzen und
mit einem stabilen SHA-256-Präfix disambiguieren.

`reclaimPolicy: Retain`: nach dem Löschen des PVC bleibt das PV in `Released`
liegen und muss manuell freigegeben werden. Für Weltdaten ist ein zusätzlicher
Aufräumschritt allemal besser als eine gelöschte Survival-Welt.

### Config-Zugriff für Admins — drei Ebenen

**Ebene 1 — Template (gilt für alle Services eines Tasks).** Das Template liegt im
Ceph-RGW-Bucket. `modules/storage-s3` spricht das ohne Codeänderung an:
`S3TemplateStorageConfig` hat bereits `endpointOverride` und `pathStyleAccess`. Der
Admin editiert per S3-Client, WebUI oder `rclone mount` auf dem eigenen Rechner —
dort ist S3-FUSE unproblematisch, weil keine Weltdaten im Spiel sind. Wirkung: jeder
neu gestartete Service des Tasks.

**Ebene 2 — Service-Config (persistente Server).** Jeder persistente Service bekommt
einen eigenen Bucket-Prefix `s3://cloudnet-configs/<service-name>/`. Synchronisiert
werden ausschließlich die in `k8s.storage.configPaths` deklarierten Globs; die Welt
wird nie angefasst.

- **Start:** Init-Container synct Bucket → PVC.
- **Laufzeit:** ein Config-Sidecar zieht Bucket-Änderungen live in den Pod.
  Anschließend wird — sofern `k8s.config.reloadCommand` am Task gesetzt ist — der
  Plugin-Reload-Befehl über den Konsolenpfad abgesetzt. Ohne gesetzten Befehl wird
  die Datei nur aktualisiert und wirkt beim nächsten Start.
- **Stop:** Rücksync PVC → Bucket, aber nur für Dateien mit neuerem `mtime`, damit
  ein Stop keinen frisch editierten Bucket-Stand überschreibt.

Das ist unbedenklich, weil Configs kleine, selten geschriebene Textdateien sind —
die Schreibverstärkung von S3 trifft nur In-Place-Sektor-Rewrites.

Nebeneffekt: Der `.cloudnet-initialized`-Marker schützt nur noch die **Welt** vor
Template-Überschreibung, nicht die Configs. Damit entfällt der klassische
CloudNet-Schmerz "ein static service zieht sein Template nie wieder".

**Ebene 3 — Live (Phase 4).** Die WebUI liest und schreibt Dateien im laufenden Pod
über `pods/exec` + `tar`, analog zu `kubectl cp`. Beim Stop landet das Ergebnis über
Ebene 2 automatisch im Bucket.

### Snapshots

`snapshot-controller` und die v1-CRDs aus `kubernetes-csi/external-snapshotter`
müssen **separat** installiert werden — Rook bringt sie nicht mit.

Ein RBD-Snapshot ist crash-consistent, nicht application-consistent. Für saubere
Backups: `save-off` + `save-all` per CloudNet-Command, snappen, `save-on`. Das ist
der eine Punkt, an dem ein CloudNet-integrierter Backup-Hook den Aufwand lohnt.

### Betriebshinweise

- Das CloudNet-Node-Image läuft als non-root `cloudnet`; PVCs brauchen
  `securityContext.fsGroup`, sonst Permission-Fehler auf `/data`.
- `CephObjectStore` mit `gateway.instances: 1` macht den TemplateStorage zum SPOF.
- Die StorageClass für Buckets braucht `reclaimPolicy: Retain` — `Delete` löscht
  das Bucket physisch samt Inhalt.
- Secret-Keys unterscheiden sich je nach Quelle: `CephObjectStoreUser` liefert
  `AccessKey`/`SecretKey`, eine `ObjectBucketClaim` dagegen
  `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`.
- Fencing ist **nicht** "einschalten und fertig": `enableFencing: true` steht auf
  `false`, csi-addons hat keinen Taint-Watcher, und Rook hat seine eigene Doku im
  Juli 2026 korrigiert (rook#17790). Es braucht zusätzlich eine `NetworkFenceClass`
  und eine Automatisierung, die den Taint setzt.

---

## 11. Routing

### mc-router, Annotation-Discovery

Geroutet wird ausschließlich nach `ServerAddress` aus dem Minecraft-Handshake;
`ServerPort` wird vollständig ignoriert. **Das Matching ist exakte
String-Gleichheit — keine Wildcards, keine Regex.** Jede Domain muss einzeln
registriert werden.

Nach dem Handshake-Replay ist mc-router ein reiner Byte-Pump (zwei `io.Copy`). Es
parst und verändert nichts mehr, funktioniert daher transparent mit Online-Mode.
Status-Pings werden durchgereicht — **der echte Velocity beantwortet die MOTD**,
CloudNets `syncproxy`-Modul funktioniert unverändert.

### Warum Annotationen und nicht die REST-API

mc-routers Routing-Tabelle ist eine prozesslokale Map. **Es gibt keinen geteilten
Zustand, kein Clustering, keine Leader-Election.** Ein `POST /routes` gegen einen
Service mit drei Pods erreicht genau einen davon — die REST-API ist faktisch
single-replica-only und damit ein SPOF am Netzwerkeingang.

K8s-Discovery ist dagegen replica-sicher: jeder Pod betreibt seinen eigenen Informer
und konvergiert unabhängig. Ein mc-router-Neustart ist ein **Non-Event**, während er
beim REST-Weg jedes Mal Datenverlust bedeutet — den unser Modul reparieren müsste,
ohne dass es überhaupt einen Health-Endpoint gäbe, der den Neustart verrät.

Die REST-API wird trotzdem aktiviert, aber nur als Debug-Pfad hinter einem
Feature-Flag.

### Der nötige Zusatz: ClusterIP-Service pro Proxy-Task

mc-router leitet das Backend aus `service.spec.clusterIP` ab. Ein Headless Service
liefert den String `"None"` und ist unbrauchbar. Außerdem hängen Annotationen am
Service — bei einem geteilten Headless Service gäbe es genau einen Annotationsort
für alle Pods, "Domain X → Proxy A, Domain Y → Proxy B" wäre nicht ausdrückbar.

**Der Gameserver-Headless-Service stört aber nicht:** ohne
`externalServerName`-Annotation ignoriert mc-router ihn vollständig.

Deshalb bekommt jeder **Proxy-Task** zusätzlich einen normalen ClusterIP-Service:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: cn-proxy-survival
  annotations:
    # rekonziliert aus ServiceTask.properties["k8s.domains"]
    mc-router.itzg.me/externalServerName: "survival.example.net,survival2.example.net"
spec:
  type: ClusterIP          # NICHT headless
  selector: { cloudnet.eu/task: survival-proxy }
  ports: [{ name: minecraft, port: 25565, targetPort: 25577 }]
```

Das ist ein guter Handel: Proxies sind wenige und langlebig, ein stabiler Service
davor ist für einen Netzwerk-Eintrittspunkt ohnehin richtig — er ist die
Failover-Einheit. mc-router routet auf die ClusterIP, kube-proxy verteilt über alle
Velocity-Pods des Tasks. Stirbt ein Proxy-Pod, fällt er aus den Endpoints; mc-router
merkt nichts und muss nichts merken. **Kein eigenes Health-Tracking, keine
Route-Updates bei Pod-Wechseln.** Die Gameserver bleiben unverändert Bare Pods ohne
eigenes Service-Objekt.

### Traffic-Eintritt

**Minecraft hat kein TLS und damit kein SNI.** Ein L4-Ingress — nginx `stream`,
HAProxy, Gateway API `TCPRoute` — sieht nur TCP-Bytes und kann prinzipiell nicht
nach Hostname routen. mc-router ist der einzige Layer, der das Minecraft-Protokoll
versteht. Ein TCP-Ingress davor wäre rein durchreichend: **die Schicht entfällt.**

```
Client -> LoadBalancer (MetalLB, :25565) -> mc-router -> Velocity (ClusterIP) -> Gameserver-Pod
```

Wildcard-A-Record `*.example.net -> <LB-IP>`, plus `-default` als Auffangnetz für
unbekannte Hostnames (sonst bekommt der Spieler eine kommentarlos geschlossene
Verbindung).

**SRV-Falle:** Bei einem SRV-Record sendet der Client den **SRV-Target-Hostnamen**
im Handshake, nicht die eingetippte Domain. Gemappt werden muss also das Target —
oder `-simplify-srv` setzen. Das ist die häufigste "warum routet der nicht"-Ursache
und gehört ins Runbook.

### PROXY-Protokoll

```
LB --(PROXY v2, optional)--> mc-router --(PROXY v2)--> Velocity --(modern)--> Gameserver
```

Velocity kann PROXY empfangen, aber nicht weiterreichen (PaperMC/Velocity#1504) — ab
dort übernimmt `modern` forwarding mit MAC-Absicherung. Die Kette ist durchgehend,
nur mit zwei Verfahren.

Zwei Sicherheitspunkte:
1. **`-trusted-proxies` ist nicht optional.** Ohne CIDR-Liste glaubt mc-router
   jedem Header — freies IP-Spoofing, das sämtliche IP-Bans und Rate-Limits umgeht.
2. Mit `haproxy-protocol = true` akzeptiert Velocity **ausschließlich** Verbindungen
   mit PROXY-Header und darf nicht mehr direkt erreichbar sein. NetworkPolicy nötig;
   Symptom bei Fehlkonfiguration ist "Client pingt endlos" (Velocity#1229).

### Betriebsparameter

- **`-connection-rate-limit` hochsetzen** (50–200). Der Default ist **1/s und
  global, nicht pro IP** — ein Server-Restart mit Reconnect-Welle wäre ein
  Selbst-DoS.
- `image.tag` pinnen; der Helm-Chart-Default ist `latest`.
- `replicas: 2` ist sicher bei reiner K8s-Discovery. Sobald Auto-Scale-Down oder
  die REST-API produktiv genutzt wird: zurück auf 1.
- Die spielerbezogenen Prometheus-Metriken sind hochkardinal (eine Zeitreihe pro
  Spieler pro Server) — für Spieler-Tracking den Webhook nutzen.
- Auto-Scale-Up ist für CloudNet weitgehend irrelevant. Falls doch gewünscht, führt
  der Weg über `-auto-scale-webhook-url`: mc-router ruft unser Modul, **wir** starten
  den Service. Dann braucht mc-router keine K8s-Scale-Rechte.

---

## 12. REST und WebUI

### Ein extern getaktetes Risiko

`CloudNet-Rest` ist ein **eigenes Repository**, nicht Teil des Hauptrepos — der
gesamte HTTP-Stack existiert dort gar nicht. Die Release-Kopplung ist gerissen:
0.5.1 stammt aus der RC15-Ära, `main` deklariert `node-impl:4.0.0-RC16-SNAPSHOT`,
und der letzte Commit liegt vor dem RC17-Release. Ob 0.5.1 auf RC17 läuft, ist
**nicht verifiziert**.

**Konsequenz für den Aufbau:** `k8s-runtime`, `k8s-storage` und `k8s-routing`
funktionieren vollständig ohne CloudNet-Rest — gesteuert über CloudNet-Commands und
Task-Properties. Die REST-Endpoints kommen als eigenes Modul mit Soft-Dependency,
das sich still deaktiviert, wenn CloudNet-Rest fehlt. Damit hängt das MVP nicht an
einem fremden, hinterherhinkenden Repo.

### Geliehene Infrastruktur

module-rest bindet den `HttpServer` ausschließlich in seinen eigenen
Injection-Layer; es gibt **keinen** `ServiceRegistry.registerProvider(HttpServer...)`.
Der Zugriff läuft über:

```java
moduleProvider.module("CloudNet-Rest").injectionLayer().instance(HttpServer.class)
```

Das funktioniert nur, weil `ModuleURLClassLoader` bewusst global auflöst und
module-rest seine Klassen unrelocated shaded. **Das ist kein dokumentierter Vertrag**
und gehört als Risiko in den Plan.

Zusätzlich ist `eu.cloudnetservice.ext:web-api` **nirgends publiziert** (Central und
derklaro.dev beide 404). Es muss in die OLF-Registry gespiegelt werden — ein eigener
Arbeitsschritt, keine Nebenbei-Aufgabe.

### Scopes

Scopes sind frei definierbar, nur regex-validiert
(`(^[a-z][a-z0-9_]{4,39}):([a-z0-9.\-_]+)`), ohne zentrale Registry. Wir nutzen
`cloudnet_k8s:`, `cloudnet_storage:` und `cloudnet_router:`.

**Achtung bei der Auswertung:** `hasOneScopeOf` hat OR-Semantik und gibt bei einem
leeren Set `true` zurück. Der Bestand annotiert Paare wie
`{service_read, service_lifecycle}`, wodurch der grobe `_read`-Scope alles
freischaltet. Unsere destruktiven Endpoints (PVC löschen, Route umbiegen) prüfen
deshalb **nur den feingranularen Scope**.

**OIDC ist nicht nachrüstbar:** `AuthProviderLoader` ist ein statischer
`ServiceLoader`-Block ohne Registrierungs-API, der mit dem Classloader des
Rest-Moduls sucht. Eine eigene `META-INF/services`-Datei bleibt wirkungslos. Phase 4
plant deshalb mit RestUser + JWT (+ Ticket für WebSockets); ein Upstream-PR wäre der
saubere Mittelweg.

### WebUI

Eine offizielle WebUI gab es **nie** — der einzige Versuch (PR #393, 2020) wurde
2021 kommentarlos geschlossen. CloudNet ist erklärtermaßen API-first.

Das WebUI-Modul liefert die REST-Endpoints; das SPA wird **separat deployed**, nach
dem Muster, das OLF mit `stelaris` bereits produktiv fährt (Bundle im Image,
gehärtetes unprivilegiertes nginx, Helm-Chart, Backend-URL zur Laufzeit aus einer
als Secret gemounteten `config.json`). Damit erzwingt ein UI-Hotfix keinen
Node-Reload.

Preis: Der CORS-Default ist `allowedOrigins("*")` **mit** `allowCredentials(true)`
— das muss auf die konkrete UI-Origin verengt werden. Zusätzlich `HttpProxyMode`
setzen, damit Client-IPs hinter dem Ingress korrekt aufgelöst werden.

Live-Updates laufen über WebSocket; **SSE unterstützt der Server nicht** (null
Treffer für `text/event-stream`).

Als Abkürzung prüfenswert: `docimin/cloudnet-webinterface` (Next.js, aktiv, deckt
Services/Tasks/Nodes ab) — nicht erweiterbar, also ein Fork. **Lizenz ist ungeprüft.**

---

## 13. Phasen

| Phase | Ergebnis | Enthält |
|---|---|---|
| **0** | Risiken beseitigt | `web-api` beschafft; 30-Sekunden-Budget gemessen; RC17/CloudNet-Rest geprüft |
| **1** | Ein Lobby-Pod läuft | Modul-Skelett, fabric8-Client, Pod-Lifecycle, Konsole, Informer, Staging-Transport |
| **2** | **Von außen spielbar (MVP)** | Proxy-ClusterIP-Service, mc-router-Annotationen, LoadBalancer, PROXY-Kette |
| **3** | Persistenz | RBD-PVC (RWOP), Config-Bucket-Sync mit Hotreload, Snapshots, Node-Loss |
| **4** | Administration | REST-Modul, SPA-Deployment |

## 14. Offene Punkte

1. **Das 30-Sekunden-Budget.** Der Wrapper bricht nach 30 s ohne Auth ab
   (`System.exit(-1)`). Scheduling, Image-Pull, Staging-Download und JVM-Boot müssen
   hineinpassen. Ist das Budget zu eng, wird Image-Prepull per DaemonSet von einer
   Optimierung zur Voraussetzung — und der Staging-Download muss in den
   Init-Container, der vor dem Timeout-Fenster läuft. **Muss in Phase 0 gemessen
   werden.**
2. **CephFS vs. RBD Small-File-Performance** ist aus der Architektur abgeleitet,
   nicht benchmarkt; es existiert kein veröffentlichter Head-to-Head. Ein
   `fio`-Lauf (4K random write) vor der Festlegung ist eine halbe Stunde wert.
3. **Lizenz von `docimin/cloudnet-webinterface`** ungeprüft — vor der
   Frontend-Entscheidung in Phase 4 klären.
4. **Versionskopplung Node ↔ Service-Image.** `launcher/libs` und `wrapper.jar` im
   Image müssen zur Node-Version passen. Der Mechanismus, der das erzwingt (Tag aus
   der CloudNet-Version ableiten und beim Start prüfen), ist noch nicht entworfen.
