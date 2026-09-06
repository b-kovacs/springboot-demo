# Solutions Reference

Concrete, copy-paste-ready fixes for the problems hit during this build. Swap in your own
names (`registry-local`, `demo-app`, node names, IP addresses) for your setup.

## WSL host setup that has to survive a reboot

`setcap` permissions needed for rootless containers don't survive a WSL restart on their
own, so they need to be reapplied at every boot through `/etc/wsl.conf`:

```ini
[boot]
systemd=true
command="setcap cap_setuid+ep /usr/bin/newuidmap; setcap cap_setgid+ep /usr/bin/newgidmap"

[interop]
enabled = true
appendWindowsPath = false
```

Kernel settings needed for cgroups, Kubernetes, and file watching, in
`/etc/sysctl.d/99-k8s.conf`:

```
net.ipv4.ip_forward=1
fs.inotify.max_user_instances=8192
fs.inotify.max_user_watches=524288
```

The inotify limits matter more than they look. Without them, controllers like Envoy
Gateway crash outright with "too many open files" once the cluster gets busy.

cgroup v2 delegation, needed for rootless `kind`, in
`/etc/systemd/system/user@.service.d/delegate.conf`:

```ini
[Service]
Delegate=cpu cpuset io memory pids
```

This needs a WSL restart to actually take effect. It fixes a "no cpuset support" error
from MetalLB and `kind`.

## Rootless buildkit as a home-manager user service

The trick is that `buildkitd` has to join containerd's already-existing rootless network
namespace instead of creating a fresh one of its own, using the rootless setup tool's
`nsenter` subcommand:

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

One thing not to do: don't add the fuse-overlayfs snapshotter flag unless containerd
actually has that snapshotter registered. It caused a "snapshotter not loaded" error. The
default snapshotter worked fine for builds.

## Recreating a `kind` cluster (handles the broken "Created" state)

```bash
nerdctl rm -f kind-control-plane kind-worker kind-worker2 2>/dev/null
kind delete cluster 2>/dev/null
kind create cluster --config ~/kind-cluster.yaml
```

## A local registry the cluster can actually pull from, over plain HTTP

First, the registry container itself, on the same network as the cluster's nodes.
Rootless nerdctl has no way to attach a running container to a network, so attach it at
creation time instead:

```bash
nerdctl run -d --name registry-local --restart=always --network kind \
  -v registry-local-data:/var/lib/registry registry:2
```

Second, make it resolvable to pods through Kubernetes' own DNS, with a plain Service and
an EndpointSlice pointing at the registry container's actual network address:

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

Third, let the nodes themselves pull from it over plain HTTP. containerd reads this
configuration dynamically, so no restart is needed:

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

The more permanent version of this: bake that same configuration into `kind-cluster.yaml`
itself, so a freshly created cluster already has it without needing to be patched by
hand afterward.

## A pull-through cache to speed up cold pulls of upstream images

Run a registry in proxy mode for each upstream you use, attached to the same network as
the cluster, with the same kind of node configuration pointing at it. Since these run as
plain standalone containers, they survive a cluster recreation, so a heavy image (like
MetalLB's routing daemon) only ever gets pulled from the real internet once, and locally
from then on:

```bash
nerdctl run -d --name registry-dockerio --restart=always --network kind \
  -v registry-dockerio-data:/var/lib/registry \
  -e REGISTRY_PROXY_REMOTEURL=https://registry-1.docker.io registry:2
# repeat the same pattern for quay.io, ghcr.io, and registry.k8s.io
```

## Image reference and pull policy for a locally built image

```yaml
image: registry-local:5000/demo-app:1.0   # NOT the bare "demo-app:1.0", which defaults to Docker Hub
imagePullPolicy: IfNotPresent
```

One thing learned the hard way here: `IfNotPresent` on a mutable tag, one that gets reused
across builds, means a node that already has that tag cached will never pull again, even
after CI pushes a genuinely fixed image under the same name. See the next entry. Either
switch to `imagePullPolicy: Always` for a tag you know will keep changing, or, better long
term, have CI push a unique tag for every single build so the tag itself changes and pull
behavior stops mattering.

## A stale image getting served despite a successful rebuild

Two separate caches can each independently hide a fixed image from the running pod.

The registry itself can silently lose an image. A plain registry container run without a
persistent volume loses everything it ever stored the moment it's restarted or recreated,
and will happily return a `404` for a tag that an earlier, genuinely successful CI run
pushed. Always give it a volume, and actually verify that volume is still attached rather
than assuming it survived some earlier recreate.

A node's own local cache can also hold an old image under the same tag. Fix it directly:

```bash
nerdctl exec <kind-node> crictl rmi <registry>/<image>:<tag>
kubectl patch deployment <name> -n <ns> --type=json \
  -p='[{"op":"replace","path":"/spec/template/spec/containers/0/imagePullPolicy","value":"Always"}]'
```

Important: a `kubectl patch` against a Flux-managed resource doesn't last. The next
reconciliation reverts it right back. Commit the same change into the actual manifest in
Git, or Flux will quietly undo the fix the next time it syncs.

## Tekton's git-clone step needs a ServiceAccount, even for a public repository

Symptom: `fatal: could not read Username for 'https://github.com': No such device or
address` from the clone step, even though running `git clone <url>` by hand, with no
credentials at all, works fine. If the `PipelineRun` doesn't explicitly set
`taskRunTemplate.serviceAccountName` to one carrying a git credentials secret, it falls
back to the namespace's plain `default` ServiceAccount, and the clone step tries (and
fails) to negotiate authentication instead of falling back to an anonymous clone. Always
pin the ServiceAccount explicitly:

```yaml
spec:
  taskRunTemplate:
    serviceAccountName: tekton-build
```

## Building a Spring Boot image: use the full jar, don't hand-roll layering

The reliable Dockerfile, where the jar contains everything needed and `java -jar` just
works:

```dockerfile
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY target/demo-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

Manually extracting the jar into layers and copying them individually produced an image
where Spring Boot's own loader classes ended up in an empty layer, so the JVM couldn't
find the class it needed to even start. Avoid that approach unless you've actually
verified both the extracted layout and that the resulting image genuinely runs, not just
that it builds.

## Fast CI builds: cache the Maven repository

In the Tekton build step, point Maven's local dependency cache at the persistent
workspace, so dependencies only get downloaded once instead of on every build:

```yaml
script: |
  mvn clean package -B -Dmaven.repo.local=$(workspaces.source.path)/.m2
```

This dropped build time from about four minutes cold to around nine seconds warm. One
constraint worth knowing: a single Tekton TaskRun can only bind one PVC-backed workspace
at a time, so don't try adding a second, separate cache volume. Use a subfolder of the
same shared workspace instead.

## Keeping Flux configuration separate from the thing that installs its custom resource type

Don't bundle something like MetalLB's IP address pool configuration together with
MetalLB's own install step. Give the configuration its own Kustomization that explicitly
depends on the install layer finishing first:

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

## The root Flux Kustomization should list layers explicitly, not scan a folder

Put an explicit `kustomization.yaml` at the cluster's root listing only the individual
layer definitions, so their declared dependencies actually get respected instead of
flattened out:

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

## HelmRelease timeouts for a cold-start install

```yaml
spec:
  timeout: 15m                 # the default 5m is too short for a cold pull
  install:
    remediation: { retries: 3 }
```

## Installing Tekton itself through Flux

Vendor Tekton's own release file into the repository and apply it through a Kustomization
with `wait: true` set, so anything that depends on Tekton's custom resource types waits
for them to actually exist first:

```bash
curl -sSL https://storage.googleapis.com/tekton-releases/pipeline/latest/release.yaml \
  -o clusters/kind/infrastructure/tekton/release.yaml
```

## Postgres holding onto a stale password on an old volume

If authentication fails with what should be the right password, the volume may hold data
that was initialized with an older one. Postgres only applies `POSTGRES_PASSWORD` the very
first time it sets up an empty data directory, and ignores it completely after that. For a
throwaway development database, the fix is to wipe it and let it reinitialize:

```bash
kubectl scale deployment/postgres -n default --replicas=0
kubectl wait --for=delete pod -l app=postgres -n default --timeout=60s
kubectl delete pvc postgres-pvc -n default
kubectl scale deployment/postgres -n default --replicas=1
```

## Making a home-manager-managed file readable inside a `kind` node

home-manager manages files by symlinking them into its own storage location under
`/nix/store`. If that file gets bind-mounted into a `kind` node, for example containerd's
registry configuration, the node's own view of the filesystem can't resolve a symlink that
points outside the one folder it was actually told to mount. Mount the Nix store itself
too, read-only, on every node:

```yaml
nodes:
  - role: control-plane
    extraMounts:
      - hostPath: /home/linux/projects/springboot-demo/registry-config
        containerPath: /etc/containerd/certs.d
      - hostPath: /nix/store
        containerPath: /nix/store
        readOnly: true
  # repeat this same pattern for every node role
```

Without this fix, the symptom on a fresh cluster is exactly the same
"server gave HTTP response to HTTPS client" error as a plain missing configuration file,
even though the file clearly "exists" when checked on the host. Always verify from inside
the actual node itself:

```bash
nerdctl exec <node> cat /etc/containerd/certs.d/<registry>/hosts.toml
```

## SOPS and age: encrypting secrets so Flux can still use them

Generate a keypair once, and keep the private key outside of Git entirely:

```bash
mkdir -p ~/.config/sops/age
age-keygen -o ~/.config/sops/age/keys.txt
```

Load the private key into the cluster so Flux can decrypt with it. This is not committed
to Git, and it's the one manual step a fresh cluster, or the full rebuild test, always
needs:

```bash
kubectl create secret generic sops-age -n flux-system \
  --from-file=age.agekey=/home/linux/.config/sops/age/keys.txt \
  --dry-run=client -o yaml | kubectl apply -f -
```

A `.sops.yaml` file at the repository root, matching files by name, and only encrypting
the actual secret payload so the rest of each manifest stays readable and diffable:

```yaml
creation_rules:
  - path_regex: .*(secret|credentials).*\.yaml$
    encrypted_regex: ^(data|stringData)$
    age: <your age public key>
```

Encrypt a plain secret manifest in place, then only ever commit the encrypted version:

```bash
sops --encrypt --in-place path/to/some-secret.yaml
```

Turn on decryption for every Flux Kustomization whose path contains an encrypted secret:

```yaml
spec:
  decryption:
    provider: sops
    secretRef:
      name: sops-age
```

When building a secret manifest from an existing live secret, write the result straight
to a file and never print or echo the decoded value:

```bash
kubectl get secret <name> -n <ns> -o json > /tmp/raw.json
python3 -c "
import json
d = json.load(open('/tmp/raw.json'))
# build a clean manifest using d['data'], still base64 at this point. write to a file, don't print it.
"
rm -f /tmp/raw.json   # or shred -u for a stronger guarantee
```

## Rotating a leaked or exposed GitHub credential

GitHub deliberately gives you no API or CLI way to create or revoke a classic or
fine-grained personal access token. That's entirely web-UI-only, by design. The practical
workaround that does stay CLI-manageable: mint a token tied to `gh`'s own OAuth app
instead of a manually created one:

```bash
gh auth refresh --hostname github.com --scopes repo,gist,read:org,workflow
# completes through a device code in a browser, after which the new token is available via:
gh auth token
```

That kind of token can be revoked later purely from the command line, since `gh auth
logout` actually calls the revoke API rather than just clearing local configuration. If
the credential you're replacing was an independent PAT instead (check by comparing a hash
of it against a hash of `gh auth token`, never print either value directly), the old one
is now simply unused, not revoked, and still needs a one-time manual revoke at
`https://github.com/settings/tokens`.

## Kubernetes health checks for a Spring Boot app, using Actuator

Plain Spring Boot has no health endpoint at all until you add it explicitly:

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

For a plain database image with no HTTP endpoint of its own, like Postgres, use a command
based check that reads the container's own environment variables (this works fine with
`envFrom`) rather than hardcoding values directly:

```yaml
readinessProbe:
  exec:
    command: ["sh", "-c", "pg_isready -U $POSTGRES_USER -d $POSTGRES_DB"]
  initialDelaySeconds: 5
  periodSeconds: 10
```

One thing worth knowing: a single restart right after adding a brand new liveness check to
an existing container isn't necessarily a sign it's misconfigured. It can simply be the
check's own startup delay racing the container's slow first-time initialization (Postgres
running its very first `initdb`, for example). Watch the restart count over a couple of
minutes before concluding the check itself is wrong.

## Unique, sortable image tags, using Tekton results that already exist

The standard `git-clone` catalog Task already exposes the commit's full SHA and its
commit timestamp as results, so no extra step is needed to get them. Build the tag at the
Pipeline level, where result substitution is resolved before each downstream step runs:

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

The timestamp prefix keeps tags sortable purely by comparing them as plain strings, which
is exactly what Flux's alphabetical image policy needs, and this stays true for as long as
a Unix timestamp remains a fixed 10-digit number, which is until the year 2286. It's still
worth filtering out unrelated or leftover tags in the image policy directly, rather than
relying on sort order alone:

```yaml
filterTags:
  pattern: '^\d{10}-[0-9a-f]{40}$'
```

## A pull-based CI trigger: a scheduled job polling a private repository

A real webhook is impossible without a way for GitHub to reach back in (see
`02-what-went-badly.md`). A scheduled job that checks the repository's latest commit is
the WSL-friendly equivalent, but it needs its own git credentials, since a pod has none by
default the way a host shell with `gh`'s own credential helper does, and it needs
permission to create the thing it triggers, including the `patch` verb, since applying an
update to an existing object needs `patch`, not just `update`:

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
      exit 1                      # fail loudly instead of falling through and treating this as "changed"
    fi
    LAST=$(kubectl get configmap build-trigger-state -n default -o jsonpath='{.data.lastSha}' 2>/dev/null || true)
    if [ -n "$LAST" ] && [ "$LATEST" = "$LAST" ]; then
      echo "no change ($LATEST)"; exit 0
    fi
    # ...create the PipelineRun here...
    kubectl create configmap build-trigger-state -n default \
      --from-literal=lastSha="$LATEST" --dry-run=client -o yaml | kubectl apply -f -
```

```yaml
# the resourceNames-scoped rule needs patch, not just update, for kubectl apply to work
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

Set `concurrencyPolicy: Forbid`, and keep the successful and failed job history limits
small, so a frequent poll doesn't leave a growing pile of Job objects behind.

Also worth checking, and the real cause of the flakiest bug in this whole project: does
anything ever create a second build while an earlier one might still be running? A
polling trigger and a manually started build can easily overlap. See the twelfth lesson in
`README.md` for the full story of what that actually broke.

## Flux image automation for a locally built image with no semantic version

```yaml
apiVersion: image.toolkit.fluxcd.io/v1   # check what your Flux version actually serves:
kind: ImageRepository                    #   kubectl api-resources | grep image.toolkit
metadata: { name: demo-app, namespace: flux-system }
spec:
  image: registry-local:5000/demo-app    # must resolve from the flux-system namespace,
  interval: 1m                           #   see the DNS entry below if it doesn't
  insecure: true                         # required for a plain HTTP registry
---
apiVersion: image.toolkit.fluxcd.io/v1
kind: ImagePolicy
metadata: { name: demo-app, namespace: flux-system }
spec:
  imageRepositoryRef: { name: demo-app }
  filterTags: { pattern: '^\d{10}-[0-9a-f]{40}$' }
  policy: { alphabetical: { order: asc } }   # works because the tag format sorts correctly, see above
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
        {{range .Changed.Changes}}{{println .}}{{end}}
        # the field is .Changed.Changes in current Flux, not the older .Updated.Images
    push: { branch: main }
  update: { path: ./clusters/kind/apps, strategy: Setters }
```

Mark the specific field to update in the Deployment. The image automation controller
rewrites the whole value, not just the tag, to exactly match the ImageRepository's
configured image plus the chosen tag:

```yaml
image: registry-local:5000/demo-app:1.0 # {"$imagepolicy": "flux-system:demo-app"}
```

The `flux-system` deploy key is read-only by default, but image automation needs to push,
so bootstrap with write access explicitly. Regenerating it requires deleting the old key
and secret first if one already exists:

```bash
gh api -X DELETE repos/OWNER/REPO/keys/<id>          # remove the old, read-only key
kubectl delete secret flux-system -n flux-system      # force a fresh keypair to be generated
flux bootstrap github --owner=OWNER --repository=REPO --path=clusters/kind --branch=main \
  --personal --read-write-key \
  --components-extra=image-reflector-controller,image-automation-controller
```

## A Service that only resolves from its own namespace

A Kubernetes Service's bare, unqualified name only resolves through CoreDNS for pods that
live in that Service's own namespace. A controller running in a different namespace, for
example the image automation controller running in `flux-system` while needing a Service
that actually lives in `default`, needs either the fully qualified name in its own
configuration, or, when the consuming configuration can't easily be changed to a
different name than every other consumer uses (a node pulling images resolves the name
through a completely separate mechanism and would break if the name changed), a scoped
CoreDNS rewrite rule, so only that one specific cross-namespace query gets redirected:

```bash
kubectl get configmap coredns -n kube-system -o jsonpath='{.data.Corefile}'
```

Add a rewrite line inside the main server block. CoreDNS reorders its plugins internally
based on its own fixed execution order regardless of where a line sits in the file, so
exact placement doesn't matter, but keeping it near the top makes the file easier to read:

```
rewrite name exact registry-local.flux-system.svc.cluster.local. registry-local.default.svc.cluster.local.
```

Two things will silently make this not work. First, a pod's resolver, with its default
`ndots` setting, tries several search-domain-expanded forms of a short name before it ever
tries the bare name itself, so a rewrite rule that only matches the bare, unqualified name
never actually fires, because CoreDNS never receives that literal query in the first
place. Check the exact form actually being queried in the failing pod's own
`/etc/resolv.conf`. Second, DNS names are absolute internally, and CoreDNS's exact-match
rewrite compares including the trailing dot. Leaving it off either side of the rule means
it silently never matches anything.

Apply with a merge patch, which avoids a conflict from reading a slightly stale version of
the ConfigMap, and confirm the reload actually happened:

```bash
kubectl patch configmap coredns -n kube-system --type merge -p '{"data":{"Corefile":"...(full content, escaped)..."}}'
kubectl logs -n kube-system -l k8s-app=kube-dns --tail=10
```

CoreDNS usually runs as multiple replicas, and a query can land on one that hasn't
reloaded yet. Retry a failing test a couple of times over ten to twenty seconds before
concluding the rule itself is wrong.

## A default-deny NetworkPolicy with explicit allows

This only restricts inbound traffic, leaving outbound traffic open, which is the safer
default for a namespace that also runs CI and needs broad outbound access to fetch
dependencies:

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

Verify the cluster's networking plugin actually enforces this before trusting it. See
[`03-debugging-playbook.md`](03-debugging-playbook.md#does-this-kubernetes-security-feature-actually-do-anything-here).

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

For a stateless app with no volume at all, like a JVM app (`/tmp` is world-writable in
most base images, so no extra volume is needed just for that):

```yaml
securityContext:
  runAsNonRoot: true
  runAsUser: 1000
  allowPrivilegeEscalation: false
  capabilities: { drop: ["ALL"] }
```

## A PodDisruptionBudget for a singleton, non-replicated workload

This doesn't provide any real high availability. It just stops a voluntary eviction, like
a node drain during a cluster upgrade, from taking down the only copy of something without
a human deciding to do that first:

```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata: { name: postgres, namespace: default }
spec:
  maxUnavailable: 0
  selector: { matchLabels: { app: postgres } }
```

## A Postgres backup job, with retention, to a dedicated volume

`pg_dump`, and libpq tools in general, specifically read a variable named `PGPASSWORD`.
Using `envFrom` alone, which exposes a secret's own key names like `POSTGRES_PASSWORD`,
isn't enough on its own:

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
    cd /backup && ls -1t "${POSTGRES_DB}"-*.dump | tail -n +8 | xargs -r rm -f   # keep the newest 7
```

A storage class that waits for a pod before actually binding a volume (`kind`'s default)
leaves this backup volume in a `Pending` state until the job's very first real run. That's
expected, not a bug. It binds properly the first time the CronJob actually fires, not the
moment the manifest is applied.

## kube-prometheus-stack through Flux, tuned for `kind`

```yaml
apiVersion: helm.toolkit.fluxcd.io/v2
kind: HelmRelease
metadata: { name: kube-prometheus-stack, namespace: monitoring }
spec:
  timeout: 15m   # cold installs need this, same reasoning as the HelmRelease timeout entry above
  install: { remediation: { retries: 3 } }
  chart:
    spec:
      chart: kube-prometheus-stack
      sourceRef: { kind: HelmRepository, name: prometheus-community, namespace: monitoring }
  values:
    # kind doesn't expose these the way the chart expects, and leaving them on just
    # produces permanently "down" targets for something that was never actually broken
    kubeControllerManager: { enabled: false }
    kubeScheduler: { enabled: false }
    kubeEtcd: { enabled: false }
    kubeProxy: { enabled: false }
    prometheus:
      prometheusSpec:
        # watch ServiceMonitors across the whole cluster, not just release-labeled ones
        serviceMonitorSelectorNilUsesHelmValues: false
        serviceMonitorNamespaceSelector: {}
        retention: 3d
        storageSpec: {}   # deliberately no persistent volume, since metrics history is disposable
```

Put this HelmRelease and anything that depends on its custom resource types (like a
`ServiceMonitor`) in separate Flux layers, with the dependent one waiting on the
installer, the same dry-run-deadlock reasoning as the MetalLB configuration split earlier.

## Exposing Spring Boot metrics for Prometheus to scrape

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
  selector: { matchLabels: { app: demo-app } }   # matches the Service's metadata.labels, not spec.selector
  endpoints:
    - port: http                                  # must match a named port on the Service
      path: /actuator/prometheus
      interval: 15s
```

The Service needs both that named port and its own `metadata.labels` set directly, not
just `spec.selector`. See the ServiceMonitor entry in
[`03-debugging-playbook.md`](03-debugging-playbook.md#a-servicemonitor-exists-the-service-routes-fine-but-prometheus-never-scrapes-it)
for the full explanation. And if a default-deny NetworkPolicy is in place, add an explicit
allow rule from the `monitoring` namespace to the scraped port, or Prometheus's own
traffic gets silently blocked the same way any other unlisted namespace's would.

## Testing a Spring Data JPA app without a real database in CI

`@SpringBootTest` boots the entire application context, including the real data source
configuration. Pointing that at a database that doesn't exist in the build environment
fails with a Hibernate error about not being able to determine the database dialect, which
doesn't look like a credentials problem at first glance. Override the datasource for tests
only, through `src/test/resources/application.properties`, since test resources take
priority over main resources when running tests:

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

And actually run the tests in CI. Skipping tests in the build step defeats the entire
point of having them:

```bash
mvn clean package -B -Dmaven.repo.local=$(workspaces.source.path)/.m2   # no -DskipTests
```

## A layered Controller, Service, and Repository, with DTOs instead of exposing a JPA entity directly

```java
public record CreateAnnouncementRequest(@NotBlank String text) {}
public record AnnouncementResponse(Long id, String text, Instant createdAt) {
    public static AnnouncementResponse from(Announcement a) {
        return new AnnouncementResponse(a.getId(), a.getText(), a.getCreatedAt());
    }
}

@Service
public class AnnouncementService {
    public AnnouncementResponse findById(Long id) {
        return repository.findById(id).map(AnnouncementResponse::from)
                .orElseThrow(() -> new AnnouncementNotFoundException(id));
    }
    // create and delete follow the same shape
}

@RestControllerAdvice
public class AnnouncementExceptionHandler {
    @ExceptionHandler(AnnouncementNotFoundException.class)
    public ResponseEntity<Map<String,String>> handleNotFound(AnnouncementNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }
}
```

Test the service with plain Mockito, no Spring context needed at all, and the controller
with a real MockMvc slice test:

```java
@ExtendWith(MockitoExtension.class)
class AnnouncementServiceTest {
    @Mock AnnouncementRepository repository;
    @InjectMocks AnnouncementService service;
    // ...
}

@WebMvcTest(AnnouncementController.class)
class AnnouncementControllerTest {
    @Autowired MockMvc mockMvc;
    @MockitoBean AnnouncementService service;   // not @MockBean, which was removed in newer Spring Boot
    // ...
}
```

## Finding a relocated class or package in an unfamiliar Spring Boot version

Don't guess. Inspect the real, published artifact from Maven Central directly. This works
for any dependency, however unfamiliar or recently changed:

```bash
curl -s ".../org/springframework/boot/spring-boot-webmvc-test/<ver>/spring-boot-webmvc-test-<ver>.pom" | grep artifactId
curl -sL ".../spring-boot-webmvc-test-<ver>.jar" -o x.jar && unzip -l x.jar | grep WebMvcTest.class
```

This found the actual answer in a couple of minutes: `@WebMvcTest` had moved to
`org.springframework.boot.webmvc.test.autoconfigure` in this Boot version, from the
long-standing `org.springframework.boot.test.autoconfigure.web.servlet`. Jackson itself
had also relocated entirely, from `com.fasterxml.jackson.databind.ObjectMapper` to
`tools.jackson.databind.ObjectMapper` under the groupId `tools.jackson.core`. Meanwhile
`MockMvc`, `MockitoBean`, and the request and result matcher builders were all exactly
where long-standing Spring convention would expect them, at their classic
`org.springframework.test.web.servlet` and
`org.springframework.test.context.bean.override.mockito` locations.

## Tekton already co-schedules Tasks that share a workspace, check before hand-rolling affinity

Tekton's Affinity Assistant feature, on by default, is built specifically for the problem
of a node-local, PVC-backed workspace needing every step of one build to land on the same
node. Verify it's actually doing its job before assuming it isn't, or before writing a fix
that duplicates or even conflicts with it:

```bash
kubectl get statefulset -n <ns> | grep affinity-assistant
kubectl get pod -n <ns> <a-task-pod> -o jsonpath='{.spec.affinity}'
# expect a required podAffinity referencing app.kubernetes.io/instance: affinity-assistant-<hash>
```

If you genuinely need a custom `podTemplate.affinity` for some other reason, know that
Tekton does not substitute pipeline context variables like the run's own name inside
`podTemplate.affinity` fields. The literal, unsubstituted string gets copied verbatim into
whatever object actually consumes it, including Tekton's own Affinity Assistant
StatefulSet if that feature is on, and fails Kubernetes' own label validation outright,
immediately and loudly.

## Reaching a service without a working LoadBalancer or ingress, the WSL fallback

`kubectl port-forward` is the reliable path on WSL, since it tunnels straight through the
Kubernetes API server and needs no actual routing between the host and the containers:

```bash
kubectl port-forward svc/<name> -n <ns> 8888:80 &
curl --header "Host: demo.localhost" http://localhost:8888/...
```
