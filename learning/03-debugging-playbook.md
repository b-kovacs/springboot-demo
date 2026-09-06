# Debugging Playbook

How to diagnose the recurring classes of problem from this build. Organized by symptom.

## First principle: slow vs. broken

On a fresh/cold cluster, most "stuck" states are **slow image pulls**, not failures.
Always check before intervening:

```bash
kubectl get pods -A                       # what's actually happening
kubectl describe pod -n <ns> <pod> | grep -A10 Events   # "Pulling" vs a real error
```

A `Pulling image ... in 5m52s` event = it was just slow. `context deadline exceeded` from
a CLI (`flux reconcile`, `kubectl wait`) is usually the *command's* timeout, not a failure —
re-check state with `flux get kustomizations` / `kubectl get pods` rather than trusting it.

## Cluster is down after a WSL restart

Symptom: `The connection to the server 127.0.0.1:PORT was refused`.

kind clusters don't survive `wsl --shutdown`. Diagnose and recreate:

```bash
kubectl get nodes                         # connection refused = cluster gone
kind get clusters                         # is it known but dead?
nerdctl ps -a | grep kind                 # nodes in 'Created'/'Exited' state?
```

Recreate (idempotent teardown handles the broken 'Created' state):

```bash
nerdctl rm -f kind-control-plane kind-worker kind-worker2 2>/dev/null
kind delete cluster 2>/dev/null
kind create cluster --config ~/kind-cluster.yaml
```

## A pod won't pull its image

Symptom: `ImagePullBackOff` / `ErrImagePull`. Get the exact reason:

```bash
kubectl describe pod -n <ns> <pod> | grep -A6 Events
```

Read the message carefully — the fixes are all different:

- `docker.io/library/<name>: not found` → **bare image name** defaulting to Docker Hub.
  The manifest is missing the registry prefix (`registry-local:5000/...`).
- `http: server gave HTTP response to HTTPS client` → node's containerd trying **HTTPS on
  a plain-HTTP registry**. Needs an insecure-registry `hosts.toml` on the nodes.
- `dial tcp: lookup <name>: no such host` → **DNS**. The registry name isn't resolvable
  from where the puller is (kubelet vs. pod DNS differ).

## A build/CI pod can't reach the local registry

Symptom (from Kaniko/Tekton): `lookup registry-local: no such host` or connection refused.

The pod resolves via cluster DNS (CoreDNS), which doesn't know Docker-network container
names. Check whether a Service/EndpointSlice exists to make it resolvable:

```bash
kubectl get svc,endpointslice -n default | grep registry
nerdctl exec kind-control-plane getent hosts registry-local   # can a node resolve it?
```

## A pod boots then crashes (CrashLoopBackOff)

The pods churn too fast to read logs normally. **Stop the loop and run a stable pod:**

```bash
kubectl scale deployment/<name> -n <ns> --replicas=0
kubectl run debug -n <ns> --restart=Never --image=<the-image> \
  --env="KEY=value" ... --command -- <the-entrypoint>
sleep 15
kubectl logs -n <ns> debug
kubectl delete pod debug -n <ns>
```

Also useful when a pod name keeps changing — target by label, and try `--previous`:

```bash
kubectl logs -n <ns> -l app=<name> --previous --tail=40 \
  2>/dev/null || kubectl logs -n <ns> -l app=<name> --tail=40
```

## App can't authenticate to the database

Symptom: `FATAL: password authentication failed`. Test each link separately —
**socket vs TCP matters** (socket often uses trust auth and hides password problems):

```bash
# from inside the DB pod, over TCP (the app's real path):
kubectl exec -n <ns> deploy/postgres -it -- env PGPASSWORD=<pw> \
  psql -h 127.0.0.1 -U <user> -d <db> -c '\conninfo'

# from another pod, to the Service (the app's actual network path):
kubectl run pgtest --restart=Never -n <ns> --image=postgres:17 --command -- \
  env PGPASSWORD=<pw> psql -h <svc> -U <user> -d <db> -c '\conninfo'
kubectl logs -n <ns> pgtest && kubectl delete pod pgtest -n <ns>
```

Also check for the classic **trailing newline in a secret** and **stale password on a
persisted PVC** (Postgres only applies `POSTGRES_PASSWORD` on *first* init of an empty
data dir — an old PVC keeps the old password):

```bash
kubectl get secret <s> -o jsonpath='{.data.PASSWORD_KEY}' | base64 -d | xxd | tail -1
kubectl get secret <s> -o go-template='{{range $k,$v := .data}}{{$k}}={{$v|base64decode}}{{"\n"}}{{end}}'
```

## A pipeline succeeded, but the pod still runs the old, broken build

Symptom: Tekton/CI reports `Succeeded`, you redeploy, and the pod crashes with an error
that should already be fixed. Don't trust "the build exited 0" — verify what the cluster
is *actually* running:

```bash
# 1. Does the registry really have this tag? (not just "did the push command exit 0")
kubectl run reg-check --restart=Never --image=curlimages/curl --command -- sleep infinity
kubectl exec reg-check -- curl -sI http://<registry-svc>:5000/v2/<image>/manifests/<tag>
# 404 here means the registry has nothing for this tag, regardless of CI history —
# e.g. an ephemeral registry container lost its storage on a restart.

# 2. What image is actually cached on each node, and since when?
nerdctl exec <kind-node> crictl images | grep <image>
```

If the registry 404s but a node still has an old image ID cached under the *same tag*,
you've found it: `imagePullPolicy: IfNotPresent` treats "tag exists locally" as good
enough and never re-pulls, so the node keeps serving a stale/broken image forever no
matter how many times CI rebuilds. Confirm by extracting the actual running artifact
instead of guessing from logs alone — override the entrypoint so the container doesn't
crash-loop while you look inside it:

```bash
kubectl run jar-inspect --restart=Never --image=<image> --command -- sleep infinity
kubectl exec jar-inspect -- ls -la /app          # does the layout match what you expect?
kubectl exec jar-inspect -- sh -c "unzip -l /app/app.jar | head"   # or read the jar directly
```

Fix: delete the stale cache entry and stop relying on a mutable tag for correctness —
see [04-solutions-reference.md](04-solutions-reference.md#stale-image-served-from-cache-despite-a-successful-rebuild).

## Flux Kustomization won't go Ready

```bash
flux get kustomizations              # which one, and the message
flux get helmreleases -A             # are the Helm installs progressing?
flux get all -A                      # everything, one view
```

Common messages and meaning:

- `dependency '...' is not ready` → ordering working; the dependency is still installing.
- `no matches for kind "X"` → the **CRD doesn't exist yet** (its installer hasn't finished),
  or a config resource is bundled with its own CRD's installer (dry-run deadlock).
- `namespace not specified` → a resource lacks a namespace and the Kustomization has no
  `targetNamespace`.
- `health check failed ... timeout waiting for Deployment` → the *manifest* applied but a
  pod isn't becoming healthy (look at that pod — usually image pull or crash).

Force a re-sync instead of waiting for the interval:

```bash
flux reconcile kustomization flux-system --with-source
flux reconcile kustomization <name>
```

## A file "exists" on the host but a container can't read it

Symptom: a bind-mounted config file behaves as if it's missing (e.g. containerd falls back
to a default instead of using it), even though `ls`/`cat` on the host confirms it's there
with the right content. Check whether it's a symlink pointing *outside* whatever got
mounted (common with declarative config tools like home-manager, which symlink into
`/nix/store`):

```bash
ls -la <the-file>                                  # is it a symlink? to where?
nerdctl exec <container> cat <path-inside-container>  # does it resolve from inside?
```

If the host `ls` shows a symlink but the in-container `cat` says "No such file or
directory," the mount doesn't cover the symlink's target — mount that target path too (see
[04-solutions-reference.md](04-solutions-reference.md#home-manager-managed-files-that-must-be-readable-inside-kind-nodes)).

## A newly-added health probe is failing

Check events, not just pod status — the message tells you whether it's a timing issue or a
real failure:

```bash
kubectl describe pod -n <ns> <pod> | grep -A5 "Liveness\|Readiness"
```

`connection refused` right after rollout usually means the probe fired before the app was
actually listening — check `initialDelaySeconds` against how long the app really takes to
start. A **single** restart coinciding with when the probe was added (not a growing count)
is often just the probe racing a slow first-time startup (e.g. Postgres's first `initdb`),
not a misconfigured probe — watch restart count over a couple of minutes before concluding
either way.

## A Service resolves for some pods but not others

Symptom: `dial tcp: lookup <svc> ... no such host` from one controller/pod, while other
pods (or the nodes themselves) reach the same name fine. Check *which* DNS mechanism the
failing consumer actually uses before touching the Service or image reference:

```bash
kubectl get pod <failing-pod> -n <ns> -o jsonpath='{.spec.serviceAccountName}{"\n"}'
kubectl exec -n <ns> <failing-pod> -- cat /etc/resolv.conf     # search domains, ndots
```

A bare Service name (`registry-local`) only resolves via CoreDNS for pods **in that
Service's own namespace** — a pod in a different namespace needs
`<svc>.<namespace>.svc.cluster.local`. Separately, **node-level operations** (image pulls
via containerd) may use a completely different resolver (on kind/nerdctl, the container
runtime's own bridge-network DNS, resolving sibling *container* names — nothing to do with
CoreDNS). Confirm which path is actually failing before deciding whether to change the
Service, the image reference, or DNS config — changing the wrong one can silently break a
path that was working. See
[04-solutions-reference.md](04-solutions-reference.md#a-service-that-only-resolves-from-its-own-namespace-cross-namespace-consumers).

## Debugging a CoreDNS `rewrite` rule that "should" match but doesn't

```bash
kubectl exec -n <ns> <pod> -- cat /etc/resolv.conf              # real search list + ndots
kubectl exec -n <ns> <pod> -- nslookup <exact-fqdn-with-trailing-dot> <coredns-clusterip>
kubectl logs -n kube-system -l k8s-app=kube-dns --tail=20        # confirm the reload happened
```

Two non-obvious gotchas: with `ndots:5` (the k8s pod default) and a name with fewer dots,
the resolver tries **search-domain-expanded forms first** — a rewrite rule matching only
the bare name never fires, because CoreDNS never receives that literal query. And CoreDNS's
`rewrite name exact` compares the **absolute DNS name including the trailing dot** — a rule
written without one can silently never match. Isolate the problem by querying CoreDNS
directly for the exact name you expect the resolver to send (from the `search`/`ndots`
output), rather than testing the human-friendly short name and guessing why it fails.

## A recurring/automated job is doing something every run when it shouldn't

Symptom: a `CronJob`-driven trigger (or similar "check state, act on change" script) acts
every single run instead of converging to "no change." Check whether a fallback meant for
one failure mode (`|| true`, `2>/dev/null`) is silently swallowing a *different* failure —
an empty/wrong value read for the "current state" check will make it look like "always
changed":

```bash
kubectl logs -n <ns> job/<latest-job>        # does it show a real value, or an empty one?
kubectl get pipelinerun -n <ns> --sort-by=.metadata.creationTimestamp | tail -5   # is the count still growing?
```

If the state read can plausibly fail (auth, RBAC, network), make that failure loud
(`exit 1` before any decision logic) rather than falling through into the "act" branch —
see [04-solutions-reference.md](04-solutions-reference.md#pull-based-ci-trigger-cronjob-polling-a-private-repo).

## Is this container actually running as root?

Nothing in routine pod status shows this — check explicitly:

```bash
kubectl exec -n <ns> deploy/<name> -- id
```

If it says `uid=0(root)`, there's no `securityContext` doing anything, regardless of what
the base image's own Dockerfile might suggest. Before adding `runAsNonRoot: true` to an
existing Deployment with a persistent volume, check what UID last wrote to that volume —
forcing a UID mismatch against existing data is a permission-denied crash waiting to
happen; see the `initContainer` chown pattern in
[04-solutions-reference.md](04-solutions-reference.md#running-postgres-as-non-root-when-the-volume-already-has-root-owned-data).

## Does this Kubernetes security feature actually do anything here?

Don't assume — test it in a throwaway namespace before relying on it for real:

```bash
kubectl create namespace netpol-test
kubectl run np-target -n netpol-test --image=nginx:alpine --labels=app=target --port=80
kubectl expose pod np-target -n netpol-test --port=80
kubectl apply -n netpol-test -f - <<'EOF'
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: deny-all }
spec: { podSelector: {}, policyTypes: ["Ingress"] }
EOF
kubectl run np-client -n netpol-test --image=curlimages/curl --restart=Never --command -- \
  sh -c "curl -s -m 5 -o /dev/null -w '%{http_code}\n' http://np-target.netpol-test.svc.cluster.local || echo BLOCKED"
# expect BLOCKED / a timeout, not a 200 - if you get a 200, this CNI doesn't enforce
# NetworkPolicy and every policy you write from here is a no-op
kubectl delete namespace netpol-test
```

A NetworkPolicy that's silently unenforced is worse than no NetworkPolicy at all — it
implies a protection that isn't there. `kindnet` (kind's default CNI) has not always
enforced NetworkPolicy; check the specific cluster, don't assume from Kubernetes docs alone.

## Backup/maintenance job fails with an auth error despite the secret being mounted

`envFrom: secretRef` sets env vars under the secret's own key names
(`POSTGRES_PASSWORD`), but the specific tool you're running may expect a different,
tool-conventional name (`pg_dump`/`psql` read `PGPASSWORD`). Check what the failing command
actually reads:

```bash
kubectl logs -n <ns> job/<name>              # the auth error usually names what's missing
```

Add an explicit `env` entry (`valueFrom.secretKeyRef`) under the name the tool expects,
alongside (not instead of) `envFrom` if other parts of the same command use the original
names.

## A ServiceMonitor exists, the Service routes fine, but Prometheus never scrapes it

No error appears anywhere for this one — check Prometheus's own generated config and live
target list, not the YAML:

```bash
kubectl exec -n <ns> <prometheus-pod> -c prometheus -- \
  wget -qO- 'http://localhost:9090/api/v1/status/config' | grep -A3 "job_name: serviceMonitor/<ns>/<name>"
kubectl exec -n <ns> <prometheus-pod> -c prometheus -- \
  wget -qO- 'http://localhost:9090/api/v1/targets?state=any'   # check droppedTargets, not just activeTargets
```

If the job exists in the config but the target is only in `droppedTargets`, look at the
relabel step referencing `__meta_kubernetes_service_label_<key>` — that checks the
**Service's own `metadata.labels`**, not `spec.selector`. Add the label directly on the
Service:

```yaml
metadata:
  labels:
    app: demo-app   # ServiceMonitor.spec.selector matches THIS, not spec.selector below
spec:
  selector:
    app: demo-app
```

## Verifying a class/package actually exists in an unfamiliar or relocated dependency

Don't guess from memory or assume a starter "must not include" something — check the real
artifact:

```bash
curl -s "https://repo1.maven.org/maven2/<group-path>/<artifact>/<version>/<artifact>-<version>.pom" | grep artifactId   # what it depends on
curl -sL "https://repo1.maven.org/maven2/<group-path>/<artifact>/<version>/<artifact>-<version>.jar" -o x.jar
unzip -l x.jar | grep <ClassName>.class    # exact package, definitively
```

This is faster and more reliable than searching docs for a specific/unfamiliar version, and
it's the only way to be *certain* — a plausible-sounding "this must not be supported"
conclusion is a hypothesis, not a finding, until checked this way.

## A Tekton step can't find a file the previous step just built

Check node placement as a first diagnostic step, but don't stop there — Tekton's built-in
Affinity Assistant (`coschedule: workspaces`, on by default) already *requires*
co-scheduling for every Task sharing a PVC-backed workspace, so a genuine node mismatch
should be structurally prevented, not just usually-avoided:

```bash
kubectl get statefulset -n <ns> | grep affinity-assistant     # is it actually running?
kubectl get pod -n <ns> -l tekton.dev/pipelineRun=<name> -o custom-columns=NAME:.metadata.name,NODE:.spec.nodeName
kubectl get pod -n <ns> <task-pod> -o jsonpath='{.spec.affinity}'   # required affinity to the assistant pod?
```

If the assistant StatefulSet exists and the Task pod's affinity correctly references it,
nodes matching is not luck — it's enforced, and a node-locality theory for the failure is
probably wrong. Look elsewhere (transient Kaniko/overlay-fs issue, a genuinely different
race) rather than reaching for "add node affinity," which in this setup would be solving an
already-solved problem — and, if written by hand rather than relying on the built-in
assistant, has its own trap: Tekton does not substitute `$(context...)` variables inside
`podTemplate.affinity`, so a hand-rolled per-run label match silently becomes a literal,
invalid string.

## Tekton pipeline debugging

```bash
tkn pipelinerun describe --last      # per-task status: which failed, which skipped
tkn pipelinerun logs --last -f       # streamed logs (stops at task boundaries — re-attach)
kubectl get taskrun <tr> -o jsonpath='{.status.conditions[0].message}'  # exact failure
```

`Failed(CouldntGetTask)` = the referenced Task doesn't exist. `TaskRunValidationFailed` =
a structural problem in the Task/Run spec (the jsonpath message names it, e.g. "more than
one PersistentVolumeClaim is bound" → a TaskRun can only have one PVC-backed workspace).

## Buildkit not reachable (nerdctl build fails)

```bash
systemctl --user status buildkit     # is the rootless buildkitd service up?
journalctl --user -u buildkit -n 50 --no-pager   # why it failed to start
```

## General "which containerd namespace / registry state" checks

```bash
nerdctl ps -a | grep -i regist                        # what registries exist
nerdctl inspect <container> | grep -i ipaddress       # a container's kind-network IP
nerdctl exec <registry> wget -qO- http://localhost:5000/v2/_catalog   # what's cached/pushed
```
