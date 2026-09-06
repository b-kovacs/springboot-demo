# Solutions Reference

Concrete, copy-paste-ready fixes for the problems hit during the build. Adjust names
(`registry-local`, `demo-app`, node names, IPs) to your setup.

## WSL host-prep that must persist (put in install.sh / wsl.conf)

**setcaps for rootless (don't survive WSL restart)** — re-apply at every boot via
`/etc/wsl.conf`:

```ini
[boot]
systemd=true
command="setcap cap_setuid+ep /usr/bin/newuidmap; setcap cap_setgid+ep /usr/bin/newgidmap"

[interop]
enabled = true
appendWindowsPath = false
```

**Kernel sysctls (cgroup/k8s/inotify)** — `/etc/sysctl.d/99-k8s.conf`:

```
net.ipv4.ip_forward=1
fs.inotify.max_user_instances=8192
fs.inotify.max_user_watches=524288
```

The **inotify** limits are critical: without them, controllers (e.g. Envoy Gateway) crash
with `couldn't initialize inotify: too many open files` on a busy cluster.

**cgroup v2 delegation for rootless kind** — `/etc/systemd/system/user@.service.d/delegate.conf`:

```ini
[Service]
Delegate=cpu cpuset io memory pids
```

(Requires a WSL restart to take effect. Fixes MetalLB/kind "No cpuset support".)

## Rootless buildkit as a home-manager user service

The trick is buildkitd must `nsenter` into containerd's *existing* rootless namespace
(not spawn a fresh one), using the rootless setuptool's `nsenter` subcommand:

```nix
systemd.user.services.buildkit = {
  Unit = { Description = "BuildKit (rootless, containerd worker)";
           PartOf = [ "containerd.service" ]; After = [ "containerd.service" ]; };
  Service = {
    Environment = [ "PATH=${pkgs.buildkit}/bin:${pkgs.rootlesskit}/bin:/usr/sbin:/usr/bin:/bin" ];
    ExecStart = "${config.home.homeDirectory}/.local/bin/containerd-rootless-setuptool.sh nsenter -- buildkitd --oci-worker=false --containerd-worker=true";
    Restart = "always"; RestartSec = 2; Type = "simple"; KillMode = "mixed";
  };
  Install.WantedBy = [ "default.target" ];
};
```

Note: do **not** add `--containerd-worker-snapshotter=fuse-overlayfs` unless containerd
actually has that snapshotter registered — it caused `snapshotter not loaded` build errors.
The default snapshotter worked for builds.

## kind: recreate cluster (survives the broken 'Created' state)

```bash
nerdctl rm -f kind-control-plane kind-worker kind-worker2 2>/dev/null
kind delete cluster 2>/dev/null
kind create cluster --config ~/kind-cluster.yaml
```

## Local registry the cluster can pull from (insecure HTTP)

**1. Registry container on the kind network** (rootless nerdctl has no `network connect`,
so attach at creation with `--network kind`; label mislabeling as `unknown-eth0` is
cosmetic — check the IP is on the kind subnet):

```bash
nerdctl run -d --name registry-local --restart=always --network kind \
  -v registry-local-data:/var/lib/registry registry:2
```

**2. Make it resolvable to *pods* via CoreDNS** (a headless-style Service + EndpointSlice
pointing at the registry's kind-network IP):

```bash
REG_IP=$(nerdctl inspect registry-local | grep -m1 '"IPAddress"' | grep -oE '10\.4\.[0-9.]+')
kubectl apply -f - <<EOF
apiVersion: v1
kind: Service
metadata: { name: registry-local, namespace: default }
spec: { ports: [{ port: 5000, targetPort: 5000 }] }
---
apiVersion: discovery.k8s.io/v1
kind: EndpointSlice
metadata:
  name: registry-local
  namespace: default
  labels: { kubernetes.io/service-name: registry-local }
addressType: IPv4
ports: [{ name: "", port: 5000, protocol: TCP }]
endpoints:
  - addresses: ["${REG_IP}"]
    conditions: { ready: true }
EOF
```

**3. Let the *nodes* pull from it over HTTP** (insecure-registry `hosts.toml` on each node;
containerd reads `certs.d` dynamically, no restart needed):

```bash
for node in kind-control-plane kind-worker kind-worker2; do
  nerdctl exec "$node" bash -c 'mkdir -p "/etc/containerd/certs.d/registry-local:5000" && cat > "/etc/containerd/certs.d/registry-local:5000/hosts.toml" <<HOSTS
server = "http://registry-local:5000"
[host."http://registry-local:5000"]
  capabilities = ["pull", "resolve"]
  skip_verify = true
HOSTS'
done
```

**Better (permanent):** bake the `certs.d` config into `kind-cluster.yaml` via
`containerdConfigPatches` + `extraMounts` so a fresh cluster has it (avoids re-patching).

## Pull-through cache (speed up cold pulls of upstream images)

Run `registry:2` in proxy mode per upstream, attached to the kind network, with node
`hosts.toml` mirrors. Persists across cluster recreation (standalone containers), so heavy
images (MetalLB FRR, etc.) pull from the internet once, then locally forever.

```bash
nerdctl run -d --name registry-dockerio --restart=always --network kind \
  -v registry-dockerio-data:/var/lib/registry \
  -e REGISTRY_PROXY_REMOTEURL=https://registry-1.docker.io registry:2
# repeat for quay.io, ghcr.io, registry.k8s.io
```

## Image reference & pull policy for a side-loaded / local image

```yaml
image: registry-local:5000/demo-app:1.0   # NOT bare "demo-app:1.0" (defaults to Docker Hub)
imagePullPolicy: IfNotPresent
```

**Caveat learned later:** `IfNotPresent` on a *mutable* tag (`:1.0` reused across builds)
means a node that already cached that tag will never re-pull, even after CI pushes a fixed
image. See the stale-image entry below. Either use `imagePullPolicy: Always` for a
mutable/dev tag, or (better long-term) have CI push a unique tag per build (e.g. the git
SHA) so the tag itself changes and pull semantics don't matter.

## Stale image served from cache despite a successful rebuild

Two independent caches can each hide a "fixed" image from the running pod:

**The registry itself may have silently lost the image.** A plain `registry:2` container
run without a persistent volume loses everything it ever stored if it's ever restarted or
recreated — and it will happily report `404` for a tag that older, genuinely-successful CI
runs pushed. Always give it a volume (see "Local registry" above uses
`-v registry-local-data:/var/lib/registry` — verify that mount is actually there and not
lost during some earlier recreate).

**A node's local containerd cache may hold an old image under the same tag.** Fix directly:

```bash
nerdctl exec <kind-node> crictl rmi <registry>/<image>:<tag>
kubectl patch deployment <name> -n <ns> --type=json \
  -p='[{"op":"replace","path":"/spec/template/spec/containers/0/imagePullPolicy","value":"Always"}]'
```

**Important:** a `kubectl patch` against a Flux-managed resource is *not durable* — the
next reconciliation reverts it. Commit the same change into the manifest in Git, or Flux
will silently undo the fix on its next sync.

## Tekton git-clone needs a ServiceAccount even for a public repo

Symptom: `fatal: could not read Username for 'https://github.com': No such device or
address` from the `git-clone` Task, even though `git clone <url>` works fine by hand with
no credentials. If the `PipelineRun` doesn't set `taskRunTemplate.serviceAccountName` to
one that carries a `git-credentials` secret, it falls back to the namespace's `default`
ServiceAccount, and the git-init step tries (and fails) to negotiate auth instead of
falling back to anonymous. Always pin the SA explicitly:

```yaml
spec:
  taskRunTemplate:
    serviceAccountName: tekton-build
```

## Spring Boot image: use the fat jar, not manual layering

The reliable Dockerfile (the jar contains the loader; `java -jar` just works):

```dockerfile
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY target/demo-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

(Manual `extract --layers` + per-layer COPY produced an empty `spring-boot-loader` layer →
`JarLauncher` ClassNotFound. Avoid unless you verify the extracted layout AND that the
resulting image actually runs.)

## Fast CI builds: cache the Maven repo (the big win)

In the Tekton maven-build task, point Maven's local repo at the (persistent) workspace so
dependencies download once:

```yaml
script: |
  mvn clean package -DskipTests -B -Dmaven.repo.local=$(workspaces.source.path)/.m2
```

Result: ~4min cold → ~9s warm. (Note: a single Tekton TaskRun can bind only **one**
PVC-backed workspace — don't try a separate maven-cache PVC; use a subdir of the shared
workspace.)

## Flux: separate CRD-dependent config from its installer (dry-run deadlock fix)

Don't bundle e.g. MetalLB's IPAddressPool with MetalLB's HelmRelease. Give the config its
own Kustomization that `dependsOn` the install layer:

```yaml
apiVersion: kustomize.toolkit.fluxcd.io/v1
kind: Kustomization
metadata: { name: metallb-config, namespace: flux-system }
spec:
  dependsOn: [{ name: infrastructure }]
  path: ./clusters/kind/metallb-config
  interval: 10m
  prune: true
  sourceRef: { kind: GitRepository, name: flux-system }
```

## Flux: root Kustomization should delegate, not recurse

Put an explicit `kustomization.yaml` at the cluster root listing only the sub-Kustomization
*definitions* (so `dependsOn` ordering is respected, not flattened):

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  - flux-system
  - infrastructure.yaml
  - metallb-config.yaml
  - apps.yaml
  - tekton.yaml
  - tekton-pipeline.yaml
```

## Flux: HelmRelease timeouts for cold-start installs

```yaml
spec:
  timeout: 15m                 # default 5m is too short for cold pulls
  install:
    remediation: { retries: 3 }
```

## Flux: install Tekton (vendored release manifest, GitOps-managed)

Vendor Tekton's release YAML into the repo and apply via a Kustomization with
`wait: true`; make the pipeline resources `dependsOn` it so CRDs exist first.

```bash
curl -sSL https://storage.googleapis.com/tekton-releases/pipeline/latest/release.yaml \
  -o clusters/kind/infrastructure/tekton/release.yaml
```

## Postgres: stale password on a persisted PVC

If auth fails with the "right" password, the PVC may hold data initialized with an old
password (Postgres ignores `POSTGRES_PASSWORD` when the data dir already exists). For a dev
DB, wipe and re-init:

```bash
kubectl scale deployment/postgres -n default --replicas=0
kubectl wait --for=delete pod -l app=postgres -n default --timeout=60s
kubectl delete pvc postgres-pvc -n default
kubectl scale deployment/postgres -n default --replicas=1
```

## home-manager-managed files that must be readable inside kind nodes

home-manager symlinks `home.file` sources into `/nix/store`. If the target is bind-mounted
into a kind node (e.g. containerd's registry config via `extraMounts`), the node's mount
namespace can't resolve a symlink pointing outside the mounted directory. Also mount the
store, read-only, on every node:

```yaml
nodes:
  - role: control-plane
    extraMounts:
      - hostPath: /home/linux/projects/springboot-demo/registry-config
        containerPath: /etc/containerd/certs.d
      - hostPath: /nix/store
        containerPath: /nix/store
        readOnly: true
  # repeat per node role
```

Symptom without this fix: `http: server gave HTTP response to HTTPS client` on a *fresh*
cluster, identical to the plain missing-config case above, even though the file "exists" on
the host — always verify from inside the node (`nerdctl exec <node> cat
/etc/containerd/certs.d/<registry>/hosts.toml`), not just on the host.

## SOPS + age: encrypting secrets for Flux

```bash
# one-time: generate a keypair, keep the private key OUTSIDE git
mkdir -p ~/.config/sops/age
age-keygen -o ~/.config/sops/age/keys.txt

# load the private key into the cluster so Flux can decrypt (NOT committed to git —
# this is the one manual step a fresh cluster/acid-test always needs)
kubectl create secret generic sops-age -n flux-system \
  --from-file=age.agekey=/home/linux/.config/sops/age/keys.txt \
  --dry-run=client -o yaml | kubectl apply -f -
```

`.sops.yaml` at the repo root (match by filename, encrypt only the actual secret payload
so the rest of the manifest stays readable/diffable):

```yaml
creation_rules:
  - path_regex: .*(secret|credentials).*\.yaml$
    encrypted_regex: ^(data|stringData)$
    age: <your age public key>
```

Encrypt a plain Secret manifest in place, then commit only the encrypted version:

```bash
sops --encrypt --in-place path/to/some-secret.yaml
```

Enable decryption on every Flux `Kustomization` whose path contains an encrypted secret:

```yaml
spec:
  decryption:
    provider: sops
    secretRef:
      name: sops-age
```

**Building a secret manifest from an existing live Secret without printing its plaintext:**
write straight to a file, never `cat`/echo the decoded value:

```bash
kubectl get secret <name> -n <ns> -o json > /tmp/raw.json
python3 -c "
import json
d = json.load(open('/tmp/raw.json'))
# build a clean manifest using d['data'] (still base64) — write to file, don't print
"
rm -f /tmp/raw.json   # or shred -u
```

## Rotating a leaked/exposed GitHub credential (no PAT create/delete API exists)

GitHub deliberately provides no API/CLI to create or revoke a classic/fine-grained personal
access token — that's web-UI-only. The CLI-manageable alternative: mint a token tied to
`gh`'s own OAuth app instead of a manually-created PAT:

```bash
gh auth refresh --hostname github.com --scopes repo,gist,read:org,workflow
# completes via device code in a browser; new token is then available via:
gh auth token
```

That token can be revoked later purely from the CLI (`gh auth logout` calls the revoke API,
not just clears local config) — but if the credential being replaced was an *independent*
PAT (check: hash-compare it against `gh auth token` — don't print either value), the old
one is now merely unused, not revoked, and still needs a one-time manual revoke at
https://github.com/settings/tokens.

## Kubernetes health probes for a Spring Boot app (Actuator)

Plain Spring Boot has **no health endpoint** — add it explicitly:

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

```properties
management.endpoint.health.probes.enabled=true
management.endpoints.web.exposure.include=health
management.endpoint.health.show-details=never
```

```yaml
readinessProbe:
  httpGet: { path: /actuator/health/readiness, port: 8080 }
  initialDelaySeconds: 10
  periodSeconds: 10
livenessProbe:
  httpGet: { path: /actuator/health/liveness, port: 8080 }
  initialDelaySeconds: 20
  periodSeconds: 15
```

For a plain database image with no HTTP endpoint (Postgres), use an exec probe referencing
the container's own env vars (works with `envFrom` secretRef) rather than hardcoding values:

```yaml
readinessProbe:
  exec:
    command: ["sh", "-c", "pg_isready -U $POSTGRES_USER -d $POSTGRES_DB"]
  initialDelaySeconds: 5
  periodSeconds: 10
```

A single restart right after adding a *new* liveness probe to an existing container isn't
necessarily a misconfiguration — it can just be the probe's `initialDelaySeconds` racing a
slow first-time startup (e.g. Postgres's first `initdb`). Watch restart count over a couple
of minutes before concluding the probe itself is wrong.

## Unique, sortable image tags from Tekton results already available

`git-clone`'s catalog Task already exposes `commit` (full SHA) and `committer-date` (epoch)
as Task results — no extra Task needed. Compose the tag at the **Pipeline** level, where
`$(tasks.<name>.results.<result>)` substitution is resolved before each downstream Task
runs:

```yaml
# Pipeline spec
results:
  - name: image-tag
    value: $(tasks.fetch-source.results.committer-date)-$(tasks.fetch-source.results.commit)
tasks:
  - name: build-push
    taskRef: { name: kaniko-build }
    params:
      - name: IMAGE
        value: $(params.image):$(tasks.fetch-source.results.committer-date)-$(tasks.fetch-source.results.commit)
```

The epoch prefix keeps tags **chronologically sortable by plain string comparison** — which
Flux's `alphabetical` image policy needs — for as long as epoch seconds stay a fixed
10-digit number (true until year 2286). Filter out unrelated/legacy tags in the
`ImagePolicy` rather than relying on sort order alone:

```yaml
filterTags:
  pattern: '^\d{10}-[0-9a-f]{40}$'
```

## Pull-based CI trigger: CronJob polling a (private) repo

No webhook is possible without inbound reachability (see `02-what-went-badly.md`). A
`CronJob` polling `git ls-remote` is the WSL-friendly equivalent — but it needs its own git
credentials (a pod has none by default, unlike a host shell with `gh`'s credential helper
configured) and RBAC to create the resource it triggers, **including `patch`** (`kubectl
apply` on an existing object needs `patch`, not just `update`):

```yaml
env:
  - name: GIT_USER
    valueFrom: { secretKeyRef: { name: git-credentials, key: username } }
  - name: GIT_PASS
    valueFrom: { secretKeyRef: { name: git-credentials, key: password } }
args:
  - |
    set -e
    LATEST=$(git ls-remote "https://${GIT_USER}:${GIT_PASS}@github.com/OWNER/REPO.git" refs/heads/main | cut -f1)
    if [ -z "$LATEST" ]; then
      echo "could not read remote HEAD (auth or network problem)" >&2
      exit 1                      # fail loudly - do NOT fall through and treat this as "changed"
    fi
    LAST=$(kubectl get configmap build-trigger-state -n default -o jsonpath='{.data.lastSha}' 2>/dev/null || true)
    if [ -n "$LAST" ] && [ "$LATEST" = "$LAST" ]; then
      echo "no change ($LATEST)"; exit 0
    fi
    # ...create the PipelineRun...
    kubectl create configmap build-trigger-state -n default \
      --from-literal=lastSha="$LATEST" --dry-run=client -o yaml | kubectl apply -f -
```

```yaml
# Role — the resourceNames-scoped rule needs patch, not just update, for kubectl apply
rules:
  - apiGroups: ["tekton.dev"]
    resources: ["pipelineruns"]
    verbs: ["create", "get", "list"]
  - apiGroups: [""]
    resources: ["configmaps"]
    resourceNames: ["build-trigger-state"]
    verbs: ["get", "update", "patch"]
  - apiGroups: [""]
    resources: ["configmaps"]
    verbs: ["create"]
```

Set `concurrencyPolicy: Forbid` and small `successfulJobsHistoryLimit`/
`failedJobsHistoryLimit` so a frequent poll doesn't accumulate Job objects.

## Flux image automation for a locally-built (non-semver) image

```yaml
apiVersion: image.toolkit.fluxcd.io/v1   # NOT v1beta2 - check what your Flux version
kind: ImageRepository                    #   actually serves: `kubectl api-resources | grep image.toolkit`
metadata: { name: demo-app, namespace: flux-system }
spec:
  image: registry-local:5000/demo-app    # must resolve from the flux-system namespace -
  interval: 1m                           #   see the DNS section below if it doesn't
  insecure: true                         # required for a plain-HTTP registry
---
apiVersion: image.toolkit.fluxcd.io/v1
kind: ImagePolicy
metadata: { name: demo-app, namespace: flux-system }
spec:
  imageRepositoryRef: { name: demo-app }
  filterTags: { pattern: '^\d{10}-[0-9a-f]{40}$' }
  policy: { alphabetical: { order: asc } }   # works because the tag format sorts correctly - see above
---
apiVersion: image.toolkit.fluxcd.io/v1
kind: ImageUpdateAutomation
metadata: { name: demo-app, namespace: flux-system }
spec:
  interval: 1m
  sourceRef: { kind: GitRepository, name: flux-system }
  git:
    commit:
      author: { name: flux-image-updater, email: fluxcdbot@users.noreply.github.com }
      messageTemplate: |
        Automated image update
        {{range .Changed.Changes}}{{println .}}{{end}}   # NOT .Updated.Images - renamed in newer Flux
    push: { branch: main }
  update: { path: ./clusters/kind/apps, strategy: Setters }
```

Mark the field to update in the Deployment (image-automation-controller rewrites the whole
value, not just the tag, to match `ImageRepository.spec.image` + the chosen tag exactly):

```yaml
image: registry-local:5000/demo-app:1.0 # {"$imagepolicy": "flux-system:demo-app"}
```

**The `flux-system` deploy key is read-only by default** — image automation needs to push,
so bootstrap with write access explicitly (regenerating requires deleting the old key/secret
first if one already exists):

```bash
gh api -X DELETE repos/OWNER/REPO/keys/<id>          # remove the old read-only key
kubectl delete secret flux-system -n flux-system      # force a fresh keypair
flux bootstrap github --owner=OWNER --repository=REPO --path=clusters/kind --branch=main \
  --personal --read-write-key \
  --components-extra=image-reflector-controller,image-automation-controller
```

## A Service that only resolves from its own namespace (cross-namespace consumers)

A Kubernetes Service's bare name (`registry-local`) resolves via CoreDNS only for pods **in
that Service's own namespace**. A controller in a different namespace (e.g.
`image-reflector-controller` in `flux-system` needing a Service that lives in `default`)
needs either the fully-qualified name in its own config, or — when the *consuming config*
can't easily be changed to a different name than what other consumers use (e.g. node-level
image pulls that resolve via a totally separate mechanism and would break with a different
name) — a **scoped CoreDNS rewrite**, so only that specific cross-namespace query gets
redirected:

```bash
kubectl get configmap coredns -n kube-system -o jsonpath='{.data.Corefile}'   # see current config
```

Add a `rewrite` line inside the main server block (before `kubernetes`; CoreDNS reorders
plugins internally by its own fixed chain regardless of file position, so exact placement
in the file doesn't matter, but keep it readable near the top):

```
rewrite name exact registry-local.flux-system.svc.cluster.local. registry-local.default.svc.cluster.local.
```

**Two things that will make this silently not work:**
1. **`ndots` search-domain expansion.** A pod's resolver (`ndots:5` by default) tries
   `<name>.<search-domain>` forms *before* the bare name for anything with fewer dots than
   `ndots`. A rewrite matching only the bare `registry-local` never fires, because CoreDNS
   never receives that literal query — match the *actual* first search-expanded form
   instead (check `/etc/resolv.conf` in the failing pod to know what that is).
2. **The trailing dot.** DNS names are absolute internally; `rewrite name exact` compares
   including the trailing dot. Without it on both the `from` and `to`, the rule silently
   never matches.

Apply with a merge patch (avoids resourceVersion conflicts from a stale read) and confirm
the reload:

```bash
kubectl patch configmap coredns -n kube-system --type merge -p '{"data":{"Corefile":"...(full content, escaped)..."}}'
kubectl logs -n kube-system -l k8s-app=kube-dns --tail=10   # look for "Reloading complete"
```

CoreDNS runs multiple replicas; a query can hit one that hasn't reloaded yet — retry a
failing test a couple of times over ~10-20s before concluding the rule is wrong.

## Default-deny NetworkPolicy with explicit allows

Ingress-only (leaves egress open — safer default for a namespace that also runs CI, which
needs broad outbound access):

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: default-deny-ingress, namespace: default }
spec:
  podSelector: {}
  policyTypes: ["Ingress"]
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: allow-postgres-from-demo-app, namespace: default }
spec:
  podSelector: { matchLabels: { app: postgres } }
  policyTypes: ["Ingress"]
  ingress:
    - from: [{ podSelector: { matchLabels: { app: demo-app } } }]
      ports: [{ port: 5432, protocol: TCP }]
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: allow-demo-app-from-gateway, namespace: default }
spec:
  podSelector: { matchLabels: { app: demo-app } }
  policyTypes: ["Ingress"]
  ingress:
    - from: [{ namespaceSelector: { matchLabels: { kubernetes.io/metadata.name: envoy-gateway-system } } }]
      ports: [{ port: 8080, protocol: TCP }]
```

Verify the CNI actually enforces this before trusting it — see
[03-debugging-playbook.md](03-debugging-playbook.md#does-this-kubernetes-security-feature-actually-do-anything-here).

## Running Postgres as non-root when the volume already has root-owned data

```yaml
spec:
  template:
    spec:
      automountServiceAccountToken: false
      securityContext:
        fsGroup: 999
      initContainers:
        - name: fix-data-perms
          image: postgres:17
          command: ["sh", "-c", "chown -R 999:999 /var/lib/postgresql/data"]
          volumeMounts:
            - { name: postgres-storage, mountPath: /var/lib/postgresql/data, subPath: pgdata }
      containers:
        - name: postgres
          securityContext:
            runAsNonRoot: true
            runAsUser: 999
            runAsGroup: 999
            allowPrivilegeEscalation: false
            capabilities: { drop: ["ALL"] }
          # ...
```

For a stateless app with no volume (e.g. a JVM app — `/tmp` is world-writable in most base
images, so no extra volume is needed for that):

```yaml
securityContext:
  runAsNonRoot: true
  runAsUser: 1000
  allowPrivilegeEscalation: false
  capabilities: { drop: ["ALL"] }
```

## PodDisruptionBudget for a singleton (no replication) stateful workload

Doesn't provide HA — just stops a *voluntary* eviction (node drain, cluster upgrade) from
taking down the only copy without a human deciding to do that first:

```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata: { name: postgres, namespace: default }
spec:
  maxUnavailable: 0
  selector: { matchLabels: { app: postgres } }
```

## Postgres backup CronJob (pg_dump to a dedicated PVC, with retention)

`pg_dump`/libpq specifically read `PGPASSWORD` — `envFrom` alone (which exposes the
secret's own key names, e.g. `POSTGRES_PASSWORD`) isn't enough:

```yaml
env:
  - name: PGPASSWORD
    valueFrom: { secretKeyRef: { name: postgres-secret, key: POSTGRES_PASSWORD } }
command: ["/bin/sh", "-c"]
args:
  - |
    set -e
    FILE="/backup/${POSTGRES_DB}-$(date -u +%Y%m%dT%H%M%SZ).dump"
    pg_dump -h postgres -U "$POSTGRES_USER" -d "$POSTGRES_DB" -F c -f "$FILE"
    cd /backup && ls -1t "${POSTGRES_DB}"-*.dump | tail -n +8 | xargs -r rm -f   # keep last 7
```

A `WaitForFirstConsumer` storage class (kind's default) leaves the backup PVC `Pending`
until a pod actually mounts it — that's expected, not a bug; it binds on the CronJob's
first real run, not at apply time.

## kube-prometheus-stack via Flux, tuned for kind

```yaml
apiVersion: helm.toolkit.fluxcd.io/v2
kind: HelmRelease
metadata: { name: kube-prometheus-stack, namespace: monitoring }
spec:
  timeout: 15m   # cold installs need this - see the HelmRelease timeout lesson elsewhere
  install: { remediation: { retries: 3 } }
  chart:
    spec:
      chart: kube-prometheus-stack
      sourceRef: { kind: HelmRepository, name: prometheus-community, namespace: monitoring }
  values:
    # kind doesn't expose these the way the chart expects - leaving them on just
    # produces permanently "down" targets for something that was never broken
    kubeControllerManager: { enabled: false }
    kubeScheduler: { enabled: false }
    kubeEtcd: { enabled: false }
    kubeProxy: { enabled: false }
    prometheus:
      prometheusSpec:
        # watch ServiceMonitors cluster-wide, not just release-labeled ones
        serviceMonitorSelectorNilUsesHelmValues: false
        serviceMonitorNamespaceSelector: {}
        retention: 3d
        storageSpec: {}   # deliberately no PVC - metrics history is disposable
```

Put this HelmRelease and anything CRD-dependent (a `ServiceMonitor`) in **separate** Flux
Kustomizations, the dependent one `dependsOn` the installer — same dry-run-deadlock
avoidance as the MetalLB config split.

## Expose Spring Boot metrics for Prometheus to scrape

```xml
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

```properties
management.endpoints.web.exposure.include=health,prometheus
```

```yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata: { name: demo-app, namespace: default }
spec:
  selector: { matchLabels: { app: demo-app } }   # matches the Service's metadata.labels!
  endpoints:
    - port: http                                  # must match a NAMED port on the Service
      path: /actuator/prometheus
      interval: 15s
```

The Service needs both the named port and its own `metadata.labels` (not just
`spec.selector`) — see the ServiceMonitor entry in
[03-debugging-playbook.md](03-debugging-playbook.md#a-servicemonitor-exists-the-service-routes-fine-but-prometheus-never-scrapes-it).
And if the `NetworkPolicy` pattern from the security round is in place, add an explicit
allow from the `monitoring` namespace to the scraped port, or Prometheus's scrape traffic
gets silently blocked the same way any other unlisted namespace's would.

## Testing a Spring Data JPA app without a real database in CI

`@SpringBootTest` boots the full context, including the real `DataSource` — pointing that
at a database that doesn't exist in the build environment fails with a Hibernate dialect
error, not something DB-credential-shaped. Override the datasource for tests only, via
`src/test/resources/application.properties` (test resources take priority on the test
classpath):

```properties
spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1
spring.datasource.driver-class-name=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=
spring.jpa.hibernate.ddl-auto=create-drop
```

```xml
<dependency>
  <groupId>com.h2database</groupId>
  <artifactId>h2</artifactId>
  <scope>test</scope>
</dependency>
```

And actually run tests in CI — `mvn clean package -DskipTests` defeats the point of having
them:

```bash
mvn clean package -B -Dmaven.repo.local=$(workspaces.source.path)/.m2   # no -DskipTests
```

## Layered Controller → Service → Repository with DTOs (instead of exposing a JPA entity directly)

```java
public record CreateMessageRequest(@NotBlank String text) {}
public record MessageResponse(Long id, String text, Instant createdAt) {
    public static MessageResponse from(Message m) { return new MessageResponse(m.getId(), m.getText(), m.getCreatedAt()); }
}

@Service
public class MessageService {
    public MessageResponse findById(Long id) {
        return repository.findById(id).map(MessageResponse::from)
                .orElseThrow(() -> new MessageNotFoundException(id));
    }
    // create/delete follow the same shape
}

@RestControllerAdvice
public class MessageExceptionHandler {
    @ExceptionHandler(MessageNotFoundException.class)
    public ResponseEntity<Map<String,String>> handleNotFound(MessageNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }
}
```

Test the service with plain Mockito (no Spring context needed), and the controller with a
real `MockMvc` slice test:

```java
@ExtendWith(MockitoExtension.class)
class MessageServiceTest {
    @Mock MessageRepository repository;
    @InjectMocks MessageService service;
    // ...
}

@WebMvcTest(MessageController.class)
class MessageControllerTest {
    @Autowired MockMvc mockMvc;
    @MockitoBean MessageService service;   // not @MockBean - removed in newer Spring Boot
    // ...
}
```

## Finding a relocated class/package in an unfamiliar Spring Boot version

Don't guess — inspect the real artifact from Maven Central directly (works for any
dependency, however unfamiliar or new):

```bash
curl -s ".../org/springframework/boot/spring-boot-webmvc-test/<ver>/spring-boot-webmvc-test-<ver>.pom" | grep artifactId
curl -sL ".../spring-boot-webmvc-test-<ver>.jar" -o x.jar && unzip -l x.jar | grep WebMvcTest.class
```

This found, in minutes: `@WebMvcTest` moved to
`org.springframework.boot.webmvc.test.autoconfigure` in this Boot version (was
`org.springframework.boot.test.autoconfigure.web.servlet`), and Jackson itself relocated
wholesale — `com.fasterxml.jackson.databind.ObjectMapper` → `tools.jackson.databind.ObjectMapper`
(groupId `tools.jackson.core:jackson-databind`) — while `MockMvc`, `MockitoBean`,
`MockMvcRequestBuilders`, and `MockMvcResultMatchers` were all unchanged at their classic
`org.springframework.test.web.servlet.*` / `org.springframework.test.context.bean.override.mockito.*`
locations.

## Tekton already co-schedules workspace-sharing Tasks — check before adding affinity

Tekton's **Affinity Assistant** (feature flag `coschedule: workspaces`, on by default) is
purpose-built for the exact problem of a node-local (e.g. `local-path`) PVC-backed
workspace needing every Task in a `PipelineRun` on the same node. Verify it's actually
doing its job rather than assuming (or hand-rolling a fix that duplicates or conflicts with
it):

```bash
kubectl get statefulset -n <ns> | grep affinity-assistant
kubectl get pod -n <ns> <a-task-pod> -o jsonpath='{.spec.affinity}'
# expect: podAffinity.requiredDuringSchedulingIgnoredDuringExecution referencing
# app.kubernetes.io/instance: affinity-assistant-<hash>
```

If you do need custom `podTemplate.affinity` for some other reason: Tekton does **not**
substitute `$(context.pipelineRun.name)` (or other context variables) inside
`podTemplate.affinity` fields — the literal, unsubstituted string gets copied verbatim into
whatever object consumes it (including Tekton's own Affinity Assistant StatefulSet, if
`coschedule` is on) and fails Kubernetes label validation outright, immediately and loudly.

## Reach a service without a working LoadBalancer/ingress (WSL fallback)

`kubectl port-forward` is the reliable path on WSL (tunnels through the API server, no
host↔container routing needed):

```bash
kubectl port-forward svc/<name> -n <ns> 8888:80 &
curl --header "Host: demo.localhost" http://localhost:8888/...
```
