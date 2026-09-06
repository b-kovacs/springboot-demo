# Debugging Playbook

How to diagnose the problems that came up repeatedly during this build, organized by
symptom. Every command here was actually run against this cluster.

## First check: is it actually broken, or just slow?

On a fresh or cold cluster, most "stuck" states are slow image pulls, not real failures.
Always check before doing anything else:

```bash
kubectl get pods -A
kubectl describe pod -n <ns> <pod> | grep -A10 Events
```

An event that says `Pulling image ... in 5m52s` just means it was slow. A
`context deadline exceeded` from a CLI command like `flux reconcile` or `kubectl wait` is
usually that command's own timeout being hit, not a real failure. Re-check the actual
state with `flux get kustomizations` or `kubectl get pods` instead of trusting the error
message from the CLI tool itself.

## The cluster is down after a WSL restart

Symptom: `The connection to the server 127.0.0.1:PORT was refused`.

`kind` clusters don't survive `wsl --shutdown`. Diagnose, then recreate:

```bash
kubectl get nodes                         # connection refused means the cluster is gone
kind get clusters                         # is it known to kind but dead?
nerdctl ps -a | grep kind                 # are the nodes stuck in Created or Exited?
```

Recreating handles the broken state cleanly:

```bash
nerdctl rm -f kind-control-plane kind-worker kind-worker2 2>/dev/null
kind delete cluster 2>/dev/null
kind create cluster --config ~/kind-cluster.yaml
```

## A pod won't pull its image

Symptom: `ImagePullBackOff` or `ErrImagePull`. Get the exact reason:

```bash
kubectl describe pod -n <ns> <pod> | grep -A6 Events
```

Read the actual message carefully, since the fix is different for each case:

- `docker.io/library/<name>: not found` means the image reference has no registry prefix,
  so Kubernetes assumed Docker Hub by default. Add the real registry prefix.
- `http: server gave HTTP response to HTTPS client` means the node's container runtime
  tried HTTPS against a registry that only serves plain HTTP. It needs an
  insecure-registry configuration file on the nodes.
- `dial tcp: lookup <name>: no such host` is a DNS problem. The registry's name isn't
  resolvable from wherever the pull is actually happening (a node pulling an image
  resolves names differently than a pod does).

## A build or CI pod can't reach the local registry

Symptom, from Kaniko or another Tekton step: `lookup registry-local: no such host`, or a
plain connection refused.

The pod resolves names through cluster DNS, which has no idea about container names on a
Docker-style network. Check whether a Service and its endpoint actually exist to make the
name resolvable at all:

```bash
kubectl get svc,endpointslice -n default | grep registry
nerdctl exec kind-control-plane getent hosts registry-local   # can a node itself resolve it?
```

## A pod starts, then crashes, over and over

The pod churns too fast to read its logs normally. Stop the loop and run one stable copy
instead:

```bash
kubectl scale deployment/<name> -n <ns> --replicas=0
kubectl run debug -n <ns> --restart=Never --image=<the-image> \
  --env="KEY=value" --command -- <the-entrypoint>
sleep 15
kubectl logs -n <ns> debug
kubectl delete pod debug -n <ns>
```

Also useful when the pod's name keeps changing between restarts. Target it by label
instead, and try the previous container's logs first:

```bash
kubectl logs -n <ns> -l app=<name> --previous --tail=40 \
  2>/dev/null || kubectl logs -n <ns> -l app=<name> --tail=40
```

## The app can't authenticate to the database

Symptom: `FATAL: password authentication failed`. Test the connection over each actual
path separately, since a local socket connection often uses trust authentication and can
hide a real password problem that only shows up over the network:

```bash
# from inside the database pod itself, over TCP, which is the app's real path:
kubectl exec -n <ns> deploy/postgres -it -- env PGPASSWORD=<pw> \
  psql -h 127.0.0.1 -U <user> -d <db> -c '\conninfo'

# from a separate pod, through the Service, which matches the app's actual network path:
kubectl run pgtest --restart=Never -n <ns> --image=postgres:17 --command -- \
  env PGPASSWORD=<pw> psql -h <svc> -U <user> -d <db> -c '\conninfo'
kubectl logs -n <ns> pgtest && kubectl delete pod pgtest -n <ns>
```

Also worth checking: a trailing newline accidentally baked into a secret's value, and a
stale password left over on an old volume (Postgres only applies `POSTGRES_PASSWORD` the
very first time it initializes an empty data directory, so an old volume keeps whatever
password it was originally created with):

```bash
kubectl get secret <s> -o jsonpath='{.data.PASSWORD_KEY}' | base64 -d | xxd | tail -1
kubectl get secret <s> -o go-template='{{range $k,$v := .data}}{{$k}}={{$v|base64decode}}{{"\n"}}{{end}}'
```

## A pipeline reports success, but the pod is still running the old, broken build

Symptom: CI reports `Succeeded`, the pod gets redeployed, and it crashes with an error
that's supposedly already fixed. Don't trust that the build exited cleanly. Check what the
cluster is actually running:

```bash
# does the registry really have this tag, not just "did the push command exit 0"?
kubectl run reg-check --restart=Never --image=curlimages/curl --command -- sleep infinity
kubectl exec reg-check -- curl -sI http://<registry-svc>:5000/v2/<image>/manifests/<tag>
```

A `404` here means the registry genuinely has nothing under that tag, no matter what CI
history claims.

```bash
# what image is actually cached on each node, and how old is it?
nerdctl exec <kind-node> crictl images | grep <image>
```

If the registry 404s but a node still has an old image cached under that same tag, that's
the answer: a pull policy of "only pull if not already present" treats "the tag exists
locally" as good enough and never re-pulls, so the node keeps serving the same stale image
forever, no matter how many times CI rebuilds it.

To confirm what's actually inside the image, rather than guessing from logs, override the
entrypoint so it doesn't immediately crash-loop, and look inside directly:

```bash
kubectl run jar-inspect --restart=Never --image=<image> --command -- sleep infinity
kubectl exec jar-inspect -- ls -la /app
kubectl exec jar-inspect -- sh -c "unzip -l /app/app.jar | head"
```

Fix: delete the stale cached image and stop relying on a reused, mutable tag for
correctness. See [`04-solutions-reference.md`](04-solutions-reference.md#stale-image-served-from-cache-despite-a-successful-rebuild).

## A Flux Kustomization won't go Ready

```bash
flux get kustomizations              # which one, and what message
flux get helmreleases -A             # are the Helm installs still progressing?
flux get all -A                      # everything in one view
```

Common messages and what they actually mean:

- `dependency '...' is not ready`: ordering is working correctly, that dependency just
  hasn't finished installing yet.
- `no matches for kind "X"`: the custom resource type doesn't exist yet, either because
  its installer hasn't finished, or because a config resource got bundled with its own
  installer in one step, which causes the dry-run deadlock described in
  `02-what-went-badly.md`.
- `namespace not specified`: a resource has no namespace set, and the Kustomization
  applying it has no default namespace configured either.
- `health check failed ... timeout waiting for Deployment`: the manifest itself applied
  fine, but a pod isn't becoming healthy. Go look at that pod directly, usually an image
  pull problem or a crash.

Force an immediate sync instead of waiting for the normal interval:

```bash
flux reconcile kustomization flux-system --with-source
flux reconcile kustomization <name>
```

## A file "exists" on the host, but a container can't read it

Symptom: a file that's bind-mounted into a container behaves as if it's missing (the
container falls back to a default instead of using it), even though `ls` or `cat` on the
host confirms the file is there with the right content. Check whether it's actually a
symlink pointing outside whatever got mounted, which is common with declarative
configuration tools like home-manager, which manage files by symlinking them into their
own internal storage:

```bash
ls -la <the-file>                                     # is it a symlink, and pointing where?
nerdctl exec <container> cat <path-inside-container>  # does it resolve from inside?
```

If the host's `ls` shows a symlink, but the same file read from inside the container says
"No such file or directory," the mount doesn't cover wherever the symlink actually points
to. Mount that location too. See
[`04-solutions-reference.md`](04-solutions-reference.md#home-manager-managed-files-that-must-be-readable-inside-kind-nodes).

## A newly added health check is failing

Check the pod's events, not just its status. The message tells you whether this is a
timing issue or an actual failure:

```bash
kubectl describe pod -n <ns> <pod> | grep -A5 "Liveness\|Readiness"
```

A `connection refused` right after a rollout usually just means the check fired before the
app was actually ready to accept connections. Compare `initialDelaySeconds` against how
long the app genuinely takes to start. A single restart that happens right when a check
was first added, rather than a restart count that keeps growing, is often just the check
racing the app's own slow first-time startup (Postgres running its first-ever
initialization, for example), not a misconfigured check. Watch the restart count over a
couple of minutes before deciding either way.

## A Service resolves for some pods, but not others

Symptom: `dial tcp: lookup <svc> ... no such host` from one specific pod, while other pods
(or the nodes themselves) reach the exact same name just fine. Check which DNS mechanism
the failing consumer is actually using before touching the Service or the image reference
at all:

```bash
kubectl get pod <failing-pod> -n <ns> -o jsonpath='{.spec.serviceAccountName}{"\n"}'
kubectl exec -n <ns> <failing-pod> -- cat /etc/resolv.conf
```

A bare, unqualified Service name only resolves through Kubernetes' own DNS for pods that
live in that Service's own namespace. A pod in a different namespace needs the full,
qualified name instead. Separately, node-level operations, like an image pull happening
through containerd, may use a completely different resolver: on `kind` with nerdctl, the
container runtime's own bridge-network DNS, which resolves sibling container names and
has nothing to do with Kubernetes DNS at all. Work out exactly which path is actually
failing before deciding whether to change the Service, the image reference, or the DNS
configuration itself. Changing the wrong one can silently break something that was
already working. See
[`04-solutions-reference.md`](04-solutions-reference.md#a-service-that-only-resolves-from-its-own-namespace-cross-namespace-consumers).

## A CoreDNS rewrite rule that "should" match, but doesn't

```bash
kubectl exec -n <ns> <pod> -- cat /etc/resolv.conf              # the real search list and ndots setting
kubectl exec -n <ns> <pod> -- nslookup <exact-fqdn-with-trailing-dot> <coredns-clusterip>
kubectl logs -n kube-system -l k8s-app=kube-dns --tail=20        # did the config actually reload?
```

Two non-obvious things that both matter here. First, with the default `ndots` setting on
a Kubernetes pod, a name with fewer dots than that setting gets tried in its
search-domain-expanded forms first, so a rewrite rule that only matches the short, bare
name never actually fires, because CoreDNS never receives that literal query in the first
place. Second, CoreDNS's exact-match rewrite rule compares the full, absolute DNS name,
including its trailing dot, so a rule written without one can silently never match
anything. Isolate the problem by directly querying CoreDNS for the exact name you'd expect
the resolver to actually send (visible in the pod's own resolv.conf), rather than testing
the short, human-friendly name and guessing why it fails.

## A recurring or automated job does something every single run when it shouldn't

Symptom: a scheduled trigger, or any similar "check state, act only if it changed" script,
takes action every run instead of settling into "no change." Check whether a fallback
meant for one specific failure case is silently swallowing a completely different one. An
empty or wrong value read for the "what's the current state" check will always look like
"something changed":

```bash
kubectl logs -n <ns> job/<latest-job>        # does it show a real value, or an empty one?
kubectl get pipelinerun -n <ns> --sort-by=.metadata.creationTimestamp | tail -5   # still growing?
```

If the state read can plausibly fail, due to authentication, permissions, or the network,
make that specific failure loud (exit with an error immediately) instead of letting it
fall through into the same code path as "yes, this genuinely changed." See
[`04-solutions-reference.md`](04-solutions-reference.md#pull-based-ci-trigger-cronjob-polling-a-private-repo).

## Is this container actually running as root?

Nothing in routine pod status shows this. Check explicitly:

```bash
kubectl exec -n <ns> deploy/<name> -- id
```

If the answer is `uid=0(root)`, there's no security context doing anything, regardless of
what the base image's own setup might suggest. Before adding a non-root requirement to an
existing deployment that already has a persistent volume, check which user last actually
wrote to that volume. Forcing a mismatched user against existing files is a
permission-denied crash waiting to happen. See the one-time ownership-fix pattern in
[`04-solutions-reference.md`](04-solutions-reference.md#running-postgres-as-non-root-when-the-volume-already-has-root-owned-data).

## Does this Kubernetes security feature actually do anything here?

Don't assume. Test it directly, in a throwaway namespace, before relying on it for real:

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
kubectl delete namespace netpol-test
```

Expect `BLOCKED` or a timeout, not a `200`. A `200` here would mean this cluster's
networking plugin doesn't actually enforce `NetworkPolicy` at all, and every policy
written from that point on is a no-op. This project's actual output from this exact test:

```
000
BLOCKED
```

## A backup or maintenance job fails with an auth error, despite the secret being mounted

Using `envFrom` with a secret sets environment variables named exactly after that secret's
own keys, like `POSTGRES_PASSWORD`. The specific tool running inside the container might
expect a different, tool-specific name instead. `pg_dump` and `psql`, for example, read
`PGPASSWORD`, not `POSTGRES_PASSWORD`. Check what the failing command actually reads:

```bash
kubectl logs -n <ns> job/<name>              # the auth error usually names what's missing
```

Fix by adding one explicit environment variable under the name the tool actually expects,
alongside the existing `envFrom`, not instead of it, if other parts of the same container
still rely on the original names.

## A ServiceMonitor exists, the Service routes fine, but Prometheus never scrapes it

No error shows up anywhere for this one. Check Prometheus's own generated configuration
and its live target list directly, not the YAML files:

```bash
kubectl exec -n <ns> <prometheus-pod> -c prometheus -- \
  wget -qO- 'http://localhost:9090/api/v1/status/config' | grep -A3 "job_name: serviceMonitor/<ns>/<name>"
kubectl exec -n <ns> <prometheus-pod> -c prometheus -- \
  wget -qO- 'http://localhost:9090/api/v1/targets?state=any'
```

Check the second command's `droppedTargets`, not just `activeTargets`. If the job shows up
in the generated config, but the target only appears in `droppedTargets`, look at the
relabel step referencing a Service label. That checks the Service's own `metadata.labels`,
not its `spec.selector`. Add the missing label directly on the Service:

```yaml
metadata:
  labels:
    app: demo-app   # the ServiceMonitor's selector matches THIS, not spec.selector below
spec:
  selector:
    app: demo-app
```

## Verifying a class or package actually exists in an unfamiliar or recently changed dependency

Don't guess from memory, and don't assume a differently named dependency must be missing
something. Check the real, published artifact directly:

```bash
curl -s "https://repo1.maven.org/maven2/<group-path>/<artifact>/<version>/<artifact>-<version>.pom" | grep artifactId
curl -sL "https://repo1.maven.org/maven2/<group-path>/<artifact>/<version>/<artifact>-<version>.jar" -o x.jar
unzip -l x.jar | grep <ClassName>.class
```

This is faster and more reliable than searching documentation for an unfamiliar version,
and it's the only way to actually be certain. A plausible-sounding "this must not be
supported" conclusion is a guess, not a finding, until it's checked this way.

## A Tekton step can't find a file the previous step just built

This one had a real, confirmed answer found the hard way, so it's worth walking through in
full rather than just giving the fix. The two things to check, in order:

```bash
kubectl get statefulset -n <ns> | grep affinity-assistant
kubectl get pod -n <ns> -l tekton.dev/pipelineRun=<name> -o custom-columns=NAME:.metadata.name,NODE:.spec.nodeName
```

Tekton has a built-in feature, on by default, that already guarantees every step of one
build lands on the same node, so a genuine node mismatch should be structurally
impossible, not just usually avoided. If that feature is present and working (the first
command shows a StatefulSet, and every task pod landed on the same node), the actual cause
is very likely a race between two separate builds sharing the same storage volume at the
same time, not a scheduling problem at all:

```bash
kubectl get pipelinerun -n <ns> -o json \
  | jq -r '.items[] | "\(.status.startTime) \(.status.completionTime) \(.metadata.name)"' | sort
```

Look for two runs whose time ranges overlap. If they do, one run's own cleanup step
(deleting old files before a fresh checkout) is very likely deleting the other run's files
while it's still running. The fix is to stop the automated trigger from starting a new
build while one is already in progress, not to fiddle with node placement, which was never
actually the problem.

## General Tekton pipeline debugging

```bash
tkn pipelinerun describe --last      # per-step status: what failed, what got skipped
tkn pipelinerun logs --last -f       # streamed logs, stops at step boundaries, reattach as needed
kubectl get taskrun <tr> -o jsonpath='{.status.conditions[0].message}'   # the exact failure reason
```

`Failed(CouldntGetTask)` means the Task being referenced doesn't exist. A
`TaskRunValidationFailed` message names the actual structural problem directly, for
example "more than one PersistentVolumeClaim is bound," which means a single TaskRun can
only use one PVC-backed workspace at a time.

## Buildkit isn't reachable, and `nerdctl build` fails

```bash
systemctl --user status buildkit                  # is the rootless buildkitd service up?
journalctl --user -u buildkit -n 50 --no-pager    # why did it fail to start?
```

## General checks for registry state and containerd namespaces

```bash
nerdctl ps -a | grep -i regist                                       # what registries exist
nerdctl inspect <container> | grep -i ipaddress                      # a container's network IP
nerdctl exec <registry> wget -qO- http://localhost:5000/v2/_catalog  # what's actually pushed
```
