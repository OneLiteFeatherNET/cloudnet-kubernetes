# Run a CloudNet service on Kubernetes

Gets a single service running as a pod, with a working console.

## Before you start

You need a Kubernetes cluster, a CloudNet 4.0.0-RC17 node, and an S3-compatible bucket
for staging.

## 1. Apply the cluster resources

```bash
kubectl create namespace cloudnet
kubectl apply -f deploy/rbac.yaml -f deploy/headless-service.yaml
```

The headless service is created once for the whole namespace, not per service.

## 2. Provide staging credentials

Create the bucket, then edit and apply the example secret:

```bash
cp deploy/staging-secret.example.yaml /tmp/staging-secret.yaml
# fill in accessKey and secretKey
kubectl apply -f /tmp/staging-secret.yaml
```

## 3. Install the module

```bash
./gradlew shadowJar
cp k8s-runtime/impl/build/libs/cloudnet-kubernetes.jar <cloudnet>/modules/
```

Restart the node. It writes `modules/CloudNet-Kubernetes/config.json` on first start —
adjust `namespace`, `nodeServiceDns` and the `staging` block there, then restart again.

## 4. Point a task at the runtime

```
tasks create Lobby minecraft_server
tasks task Lobby set runtime k8s-jvm
```

## 5. Start it and watch

```
service create 1 Lobby --start
```

```bash
kubectl -n cloudnet get pods -l cloudnet.eu/task=Lobby -w
```

The pod moves through `Pending` into `Running`, and the node console shows the service
as `RUNNING`. Passing through `Pending` must not stop the service — the module keeps a
three minute startup grace window for exactly that reason.

## 6. Check the console both ways

```
service Lobby-1 screen
```

Server log output should appear, and typing `list` should produce an answer. That
proves stdin over `attach` and stdout over `watchLog` at the same time.

## 7. Verify cleanup

```
service Lobby-1 stop
```

```bash
kubectl -n cloudnet get pods,secrets -l cloudnet.eu/task=Lobby
```

Nothing should remain. The staging object is removed as well.

## When it does not work

**The first console command returns 403.** `pods/attach` is its own subresource;
granting `pods/exec` does not cover it. The failure only appears at runtime, never at
startup.

**The wrapper never connects and the pod exits after 30 seconds.** The wrapper gives up
with `System.exit(-1)` if it cannot authenticate in time, and scheduling, image pull,
staging download and JVM boot all have to fit inside that window. Check whether the
image was pulled cold — if so, pre-pull it onto the gameserver nodes with a DaemonSet.

**The service starts but no proxy can reach it.** Check that
`publishNotReadyAddresses: true` is set on the headless service. Without it the DNS
record only appears once the pod is ready, and the handshake races CoreDNS.

**The service binds to the wrong address.** `server-ip` must be `0.0.0.0` inside the
pod; the FQDN belongs in the service snapshot, not in `server.properties`. The module
rewrites this automatically — if it did not take effect, check that the environment's
preparer ran at all.

**`No service factory for runtime k8s-jvm`.** The service was scheduled onto a node
without this module. Either install it on every node, or rely on the node select
listener that pins these services to a node providing the factory.

**The world was silently reset.** CloudNet decides "first startup" purely by whether
the service directory exists. With ephemeral storage that is always true, so templates
overwrite persisted data without any error. This is what the storage module addresses;
until then, do not run persistent servers on this runtime.
