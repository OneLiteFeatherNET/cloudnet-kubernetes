# cloudnet-kubernetes

Runs CloudNet v4 services as Kubernetes pods instead of local JVM processes or docker
containers.

The runtime module works on its own. Storage (Ceph/RBD) and domain routing
(mc-router) are separate, optional modules that build on top of it — the runtime never
references either.

## Status

| Module | State |
|---|---|
| `k8s-runtime` | in development — runtime core complete, cluster verification pending |
| `k8s-routing` | planned |
| `k8s-storage` | planned |
| `k8s-rest` | planned |

## How it works

CloudNet stays the control plane. There are no CRDs and no second operator: the module
creates bare pods directly and reads their state from a shared informer.

Three decisions carry the design:

**Addresses are derivable before the pod exists.** Each pod sets `spec.hostname` and
`spec.subdomain` against a single headless service, which yields a predictable
`<pod>.<service>.<namespace>.svc.cluster.local`. CloudNet fixes a service's address in
`prepareService()`, while no pod exists yet — so anything resolved after creation would
be too late. A welcome consequence: the bridge modules need no changes at all, because
they only ever read `ServiceInfoSnapshot#address()`.

**Everything the pod needs travels through object storage.** The docker module
bind-mounts host paths onto identical container paths, which is the only reason its
classpath of absolute node paths works. That cannot exist across hosts. Instead the node
runs `prepareService()` unchanged on local disk and uploads two archives:

- the service directory, unpacked into `/cloudnet/service`
- a runtime bundle with the wrapper jar and every launcher library the node's JVM command
  line refers to, unpacked into `/cloudnet/runtime`

The runtime bundle is content-addressed (`runtime/<sha256>.tar`). Services with the same
runtime share one object, and it is uploaded only when that object does not exist yet.
Runtime bundles are never deleted with a service. The pods run a stock JRE image
(`eclipse-temurin:25-jre` by default), so no custom image has to match the node version.

The connection key does not travel in either archive — it ships as a Kubernetes secret,
which works because the wrapper reads its config path from an overridable system
property.

**Pod state comes from an informer, never an API call.** CloudNet's watchdog calls
`alive()` once per second for every local service. Anything else would be an outage.

## Requirements

- CloudNet `4.0.0-RC17`
- Kubernetes 1.30+
- An S3-compatible bucket for staging (Ceph RGW works; `storage-s3` already speaks to it
  through `endpointOverride` and `pathStyleAccess`)
- A JRE 25 image for the pods. The default `eclipse-temurin:25-jre` needs nothing built
  by this project.

## Installation

```bash
kubectl apply -f deploy/rbac.yaml -f deploy/headless-service.yaml
cp k8s-runtime/impl/build/libs/cloudnet-kubernetes.jar <cloudnet>/modules/
```

Then, in the node console:

```
tasks create Lobby minecraft_server
tasks task Lobby set runtime k8s-jvm
service create 1 Lobby --start
```

See [docs/how-to/run-a-service-on-kubernetes.md](docs/how-to/run-a-service-on-kubernetes.md)
for the full walkthrough, including the failure modes that are easy to hit.

## Building

```bash
./gradlew build          # compiles and runs the tests
./gradlew shadowJar      # produces cloudnet-kubernetes.jar
```

The jar shades and relocates its dependencies rather than declaring them as module
libraries. CloudNet's `ModuleURLClassLoader` resolves across every loaded module on a
miss, so an unrelocated Jackson or Bouncy Castle would leak into unrelated modules.

## Design documents

- [Design](docs/superpowers/specs/2026-08-28-cloudnet-kubernetes-operator-design.md)
- [Runtime implementation plan](docs/superpowers/plans/2026-08-28-k8s-runtime-kern.md)
