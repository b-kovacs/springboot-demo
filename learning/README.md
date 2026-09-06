# Homelab Platform — Build Learnings

A record of building a full, self-hosted, GitOps-managed Kubernetes platform on a
rootless, declarative base running under WSL2 — and everything learned (the good,
the bad, and the debugging) along the way.

## Purpose

The goal was twofold:

1. **Make an "immutable / reproducible system"** — an environment defined declaratively,
   rebuildable from source, where nothing important lives only in someone's head or in
   an un-tracked manual step.
2. **Learn Kubernetes close to production** — not toy clusters, but the real shapes:
   multi-node, container builds, ingress/gateway, load balancing, persistent storage,
   GitOps CD, and in-cluster CI.

## What was built

A complete platform, layer by layer:

- **Declarative OS layer** — Ultramarine Linux under WSL2, whole user environment defined
  in a Nix / home-manager flake, reproducible via an `install.sh` bootstrap.
- **Rootless container runtime** — containerd + nerdctl + buildkit, all as declarative
  user systemd services. No Docker daemon, no root.
- **Multi-node Kubernetes** — a 3-node `kind` cluster (vanilla upstream), plus a
  pull-through registry cache and a local push registry.
- **A real application** — a Spring Boot 4 + JPA app, developed and debugged in VS Code
  (Remote-WSL), containerized with the local buildkit.
- **Networking** — **Gateway API** (the Kubernetes-native routing spec: `GatewayClass` /
  `Gateway` / `HTTPRoute`) via Envoy Gateway (hostname routing) and MetalLB for LoadBalancer
  IPs. Worth being precise about the name: "Gateway API" (this) and "API Gateway" (the
  functional pattern — auth, rate limiting, protocol translation) are different concepts
  that happen to be implemented by the same component here; Envoy Gateway *could* also
  provide the API-gateway features via its own `SecurityPolicy`/`BackendTrafficPolicy`
  CRDs, but none of those are configured yet — see open gaps.
- **Persistent storage** — PVCs, proven to survive pod deletion.
- **GitOps CD** — Flux reconciling the entire stack from a `flux-infra` Git repo;
  proven to rebuild the whole stack (`demo-app` and `postgres` included) after a full
  `kind delete cluster` + recreate + `flux bootstrap`, with a single manual step
  (re-provisioning the SOPS age key — see lesson four below).
- **Secrets in Git, safely** — SOPS + age; no plaintext secret in `flux-infra`.
- **In-cluster CI, fully automated** — Tekton (itself Flux-managed): clone → Maven build
  (cached) → Kaniko image build → push a unique, chronologically-sortable tag. A
  `CronJob` polls the source repo (pull-based — a real webhook is impossible on this
  WSL/NAT setup) and triggers a build on every new commit, no manual step.
- **Flux image automation** — `ImageRepository`/`ImagePolicy`/`ImageUpdateAutomation` watch
  the registry, pick the newest build, and auto-commit the Deployment update back into
  `flux-infra`. Verified end-to-end: a real code change flowed from `git push` all the way
  to a running pod with zero manual intervention.
- **Production hardening (in progress)** — health probes + resource limits (lesson five),
  CI/CD maturity (lessons six/seven), security + reliability (lessons eight/nine: default-deny
  `NetworkPolicy`, non-root containers, `automountServiceAccountToken: false`, PDBs,
  Postgres backups), and observability (lesson ten: `kube-prometheus-stack` via Flux,
  `demo-app` scraped for real JVM/HTTP metrics) all done. TLS + API-gateway features on the
  Gateway are the one open next step.
- **Real application code, not a skeleton** — the app's `Message` CRUD was refactored from a
  JPA entity exposed directly over REST (no service layer, no DTOs, no tests) into a
  properly layered Controller → Service → Repository with request/response DTOs, a
  not-found exception + `@RestControllerAdvice`, and both unit tests (Mockito) and a real
  `MockMvc` slice test (lesson eleven) — with CI actually running them (`-DskipTests` removed).

## The documents

- **[01-what-went-well.md](01-what-went-well.md)** — decisions and patterns that paid off.
- **[02-what-went-badly.md](02-what-went-badly.md)** — friction, dead-ends, and the
  environmental walls (mostly WSL/rootless) that cost the most time.
- **[03-debugging-playbook.md](03-debugging-playbook.md)** — how to diagnose the recurring
  classes of problem, with the exact commands.
- **[04-solutions-reference.md](04-solutions-reference.md)** — concrete fixes for each
  problem, copy-paste ready.
- **[05-architecture-and-impact.md](05-architecture-and-impact.md)** — how the pieces fit,
  why each exists, and what it's worth.
- **[06-open-gaps-and-next-steps.md](06-open-gaps-and-next-steps.md)** — what's still
  manual / uncommitted, and the punch-list to finish the "fully reproducible" goal.

## The single biggest lesson

**Anything not committed to Git does not survive a fresh cluster.** Every painful
detour late in the build traced back to a resource created by hand (a ServiceAccount, a
Secret, a Service, a node-level containerd patch) that vanished on cluster recreation.
That is the entire reason GitOps — and encrypted-secrets-in-Git (SOPS/Sealed Secrets) —
exist. The lesson was learned the hard way, which is the way it sticks.

## The second big lesson: "the pipeline succeeded" ≠ "the cluster is running that build"

`demo-app`'s CrashLoopBackOff — the open issue at the end of the previous session — turned
out to have nothing to do with the database or the Dockerfile. Two infra facts compounded:

1. **`registry-local` returned `404` for a tag that multiple "successful" Tekton runs had
   supposedly pushed**, despite actually having a persistent volume (`registry-local-data`)
   — so the naive "it must have lost its storage" theory (my first guess) was wrong. The
   likely real cause: the EndpointSlice/IP the pods used to reach it didn't match the
   container's address at push time. Correction noted here because it was stated wrong in
   an earlier version of this doc — the volume was fine; don't trust the first plausible
   theory without checking it against the actual evidence.
2. **A worker node still had an old, broken image cached under that same mutable tag**
   (`demo-app:1.0`). With `imagePullPolicy: IfNotPresent`, kubelet saw the tag already
   existed locally and never re-pulled — so every subsequent rebuild was invisible to the
   running Deployment, no matter how many times the pipeline reported success.

The fix required proving the actual state instead of trusting status output: query the
registry's own API for the manifest (not "did the TaskRun exit 0"), and check what image
digest is *actually cached on the node* (`crictl images`) versus what the registry
currently serves. Once both were confirmed stale, deleting the cached image
(`crictl rmi`) and switching to `imagePullPolicy: Always` closed the loop — the same class
of fix as lesson one: **a mutable tag plus a cache (registry or node) is a hidden
"not really in sync with Git/CI" gap, just like an uncommitted manual resource.**

Full diagnosis trail: [03-debugging-playbook.md](03-debugging-playbook.md#a-pipeline-succeeded-but-the-pod-still-runs-the-old-broken-build),
copy-paste fixes: [04-solutions-reference.md](04-solutions-reference.md#stale-image-served-from-cache-despite-a-successful-rebuild).

## The third lesson: the acid test finds bugs *in the fix*, not just in the original setup

Ran the real test — `kind delete cluster` → recreate from `~/kind-cluster.yaml` → `flux
bootstrap` → re-provision the SOPS age key → watch everything come up. It worked, but not
on the first try, and the failure was caused by a fix from *this same session*: making
`registry-config`'s files declarative via home-manager turned them into symlinks into
`/nix/store`. kind's `extraMounts` only bind-mounts the `registry-config` directory itself
into each node container — it doesn't also expose `/nix/store`, so from inside a node's
mount namespace the symlink target didn't exist. containerd silently found no `hosts.toml`
and fell back to HTTPS, so `demo-app` went `ImagePullBackOff` with the exact
"http: server gave HTTP response to HTTPS client" error from lesson two's playbook, on a
completely fresh, otherwise-correct cluster.

Fix: also bind-mount `/nix/store` (read-only) into every node in `kind-cluster.yaml`.

**The lesson:** making something declarative is itself a change that needs testing, not
just a paperwork exercise that closes a gap. "Committed to Git" and "actually works on a
from-scratch rebuild" are different claims — the acid test is what tells them apart, and
it's worth re-running after *any* change to the reproducibility path, not just once at the
end.

## The fourth lesson: SOPS closes the secrets gap, but the age key itself is the one thing that can't be in Git

Setting up SOPS + age was mechanical (generate keypair, load the private key as a cluster
Secret, add `.sops.yaml`, encrypt, add `spec.decryption` to the relevant Flux
Kustomizations) — the one genuinely easy-to-miss step is that the acid test's fresh cluster
comes up with `flux-system`, `infrastructure`, and `tekton`/`flux-system` Kustomizations
Ready, but **`apps` and `tekton-pipeline` stay stuck failing to decrypt** until the
`sops-age` Secret is re-created from the *local* key file. That's not a bug — the age
private key deliberately isn't in Git (that would defeat the point of encrypting secrets
with it) — but it means "zero manual steps" for a from-scratch rebuild is one asterisk:
there's always going to be exactly one out-of-band secret (the decryption key itself) that
a human has to bring back. Document *where that key lives* as carefully as the rest of the
stack, because losing it means every encrypted secret in Git becomes unrecoverable.

**Related: rotating a leaked/exposed credential.** GitHub deliberately provides no
API/CLI to create or delete a classic or fine-grained personal access token — that's
web-UI-only, permanently. The practical workaround: use `gh auth refresh` (device-code
flow) to mint a token tied to `gh`'s own OAuth app instead of a manually-created PAT. That
token *can* be fully managed from the CLI going forward, including revocation
(`gh auth logout` calls the API, not just clears local config) — so switching to it trades
a small amount of scope precision for never needing the tokens web page again.

## The fifth lesson: "it runs" and "Kubernetes knows it's healthy" are different claims

Both `demo-app` and `postgres` had run successfully for this entire build with **no
liveness/readiness probes and no resource requests/limits**. That's easy to miss because
nothing visibly breaks — a pod with no probes just shows `Running` forever, whether or not
the process inside is actually serving traffic, and a pod with no resource limits just
works fine right up until something on the node contends for memory/CPU and there's no
guardrail. Kubernetes' entire self-healing value proposition (replace an unhealthy pod,
protect nodes from noisy neighbors) is opt-in per container.

Two things worth remembering for next time:
- **Plain Spring Boot has no health endpoint.** `spring-boot-starter-actuator` plus
  `management.endpoint.health.probes.enabled=true` gets you `/actuator/health/liveness` and
  `/actuator/health/readiness` for free — but it has to be added deliberately; it's not
  implied by having a web app.
- **A probe added at rollout time can cause a one-time restart that looks alarming but
  isn't.** `postgres` restarted once right after the liveness probe was added — the probe's
  `initialDelaySeconds` raced the container's first-ever `initdb`. Watch restart count over
  a couple of minutes, not just the first reading, before concluding a probe is misconfigured.

See [Deployment/Postgres in demo-app.yaml](https://github.com/b-kovacs/flux-infra/blob/main/clusters/kind/apps/demo-app.yaml)
for the actual probe/resource values used.

## The sixth lesson: two independent DNS mechanisms coexist in this cluster, and confusing them nearly caused a wrong fix

Setting up Flux image automation, `ImageRepository` (running as a pod in `flux-system`)
couldn't resolve `registry-local`, even though every other consumer of that name — Tekton
pods, the deployed app — resolved it fine. The instinctive fix (change the image name to
the fully-qualified `registry-local.default.svc.cluster.local`) would have been **wrong**,
and understanding why is the actual lesson:

- **Pod-to-Service traffic** (Tekton's git-clone talking to the registry, `curl` from a
  debug pod) resolves names via **Kubernetes CoreDNS**, which only resolves a bare
  unqualified Service name for pods *in that Service's own namespace* — `default`, in this
  case. A pod in `flux-system` needs the qualified name.
- **Node-level image pulls** (containerd on a kind node, pulling `registry-local:5000/...`
  for a Deployment) resolve names via **nerdctl's own bridge-network DNS** — the same
  mechanism Docker/nerdctl uses to resolve sibling *container names* on a shared network.
  This has nothing to do with CoreDNS at all; the node is a container on the `kind` network,
  and `registry-local` is a sibling container name on that same network.

These two paths happen to both work today only because the k8s Service/EndpointSlice for
`registry-local` was deliberately given the same IP as the actual container. Changing the
*image reference itself* to the FQDN would have fixed the controller's scan but broken
every node-level pull (nerdctl's network DNS doesn't know Kubernetes Service FQDNs).
**The correct fix touches only the consumer that's actually broken**: a CoreDNS `rewrite`
rule, scoped to queries coming from `flux-system` for `registry-local`, redirecting them to
the qualified name — leaving the node/nerdctl path and every other pod's bare-name lookups
completely untouched. See [04-solutions-reference.md](04-solutions-reference.md#a-service-that-only-resolves-from-its-own-namespace-cross-namespace-consumers)
for the exact CoreDNS syntax that actually worked (it took three attempts — `ndots`-driven
search-domain expansion and the trailing-dot absolute-name form both mattered).

## The seventh lesson: a fail-open helper script is a silent, unbounded-cost bug

The CI trigger `CronJob` (polls the app repo, creates a `PipelineRun` on a new commit) had
two independent bugs — no git credentials for the private repo, and a missing RBAC `patch`
verb — that individually would have just been visible failures. Combined, they were worse:
`git ls-remote` failed silently into an empty string (masked by a `|| true` fallback meant
for a *different* failure case — "state configmap doesn't exist yet"), an empty "latest
commit" never equalled the stored one, so the script concluded "new commit" and triggered a
build **every single 2-minute cycle, forever**, and the final "save state" step then also
failed (RBAC), so nothing ever converged. It ran unnoticed for ~35 minutes before being
caught by actually watching pipeline counts over time rather than checking the manifest for
correctness.

**The general principle:** in automation that *takes an action* (not just reports), prefer
failing loudly and doing nothing over silently defaulting to a value that makes the "act"
branch look correct. `|| true` and `2>/dev/null` are fine for genuinely optional reads;
they're a hazard on the one read whose failure should abort the whole run. The fix was
explicit: check the critical value is non-empty and `exit 1` immediately if not, before any
decision logic runs at all.

## The eighth lesson: "nothing broke" is not the same as "it's secure" — both containers had been running as root the whole time

Neither `demo-app` nor `postgres` had ever had a `securityContext`. Both ran as `uid=0`
from the very first successful deploy, through every subsequent rebuild, restart, and
acid-test rebuild — completely invisible, because running as root doesn't *look* different
from any other container in `kubectl get pods`, and nothing in this build's testing (probes,
image rebuilds, the acid test) happens to check *who* a process runs as. It took a
deliberate, specific check (`kubectl exec ... -- id`) to surface it.

Fixing it wasn't free: Postgres's official image is normally trusted to start as root and
drop its own privileges internally, but since it had *already been running as root* for
real (its data directory was written to by root), forcing it to start as non-root directly
needed a one-time `initContainer` (still root, just for this) to `chown` the existing volume
before the main container could run as `999:999`. **The general shape:** a security
property that "would have been free if set from day one" can require a real, careful
migration step once real state already exists under the old assumption — check for this
class of problem *before* accumulating state under insecure defaults, not after.

## The ninth lesson: before trusting a Kubernetes security feature, prove it's actually enforced here

Before writing any `NetworkPolicy`, the CNI in this cluster was checked empirically —
deploy a deny-all policy in a throwaway namespace, confirm a test client actually gets
blocked — rather than assumed from "Kubernetes supports NetworkPolicy." This mattered:
`kindnet` (kind's default CNI) has, at various points in its history, not enforced
NetworkPolicy at all, silently accepting the resource while doing nothing with it — the
single most dangerous kind of "security" feature, one that looks configured and isn't.
This kind version's `kindnet` does enforce it, confirmed by the test actually blocking
traffic — but the lesson is the habit, not the specific result: **a security control that
degrades to a no-op without any error is worse than not having it**, because it actively
suggests a protection that isn't there. Test the mechanism before relying on it, every time,
regardless of what the last cluster or the docs said.

## The tenth lesson: a ServiceMonitor's selector matches the Service's labels, not its selector — an easy, silent mix-up

Wiring Prometheus up to scrape `demo-app` looked complete: `ServiceMonitor.spec.selector:
matchLabels: {app: demo-app}`, a Service with `spec.selector: {app: demo-app}` routing to
the right pods, a named port matching the `ServiceMonitor`'s endpoint. Nothing errored.
Prometheus just never scraped it — the target didn't even appear as "down," it wasn't
there at all in the normal target list.

The actual mechanism: a `ServiceMonitor` selects **Services by their own `metadata.labels`**
(which Kubernetes copies onto the Service's Endpoints/EndpointSlice objects), completely
independent of that Service's `spec.selector` (which only controls which *pods* it routes
to). The `demo-app` Service had `spec.selector` set — routing worked fine for real traffic —
but no `metadata.labels` of its own, so the `ServiceMonitor`'s `matchLabels: {app: demo-app}`
never matched *the Service*, regardless of matching every pod perfectly.

**Where it actually showed up:** not as an error, but in Prometheus's `/api/v1/targets`
under `droppedTargets` rather than `activeTargets` — discovered via service discovery,
then silently filtered out by a relabel rule checking
`__meta_kubernetes_service_label_app` (empty) against `demo-app`. Found by reading
Prometheus's own generated scrape config (`/api/v1/status/config`) line by line for the
job in question, not by staring at the YAML — the YAML looked completely correct in
isolation; the bug was in a cross-object assumption neither file states explicitly.

## The eleventh lesson: don't guess a fictional/future framework's API — go read the real, published artifact

Writing a `MockMvc`-based controller test failed to compile: `@WebMvcTest` and
`com.fasterxml.jackson.databind.ObjectMapper` both "didn't exist." The tempting conclusion —
"this project's renamed test starter must just not include the full web-testing stack" —
was wrong, and abandoning the MockMvc test for a same-day plain-Mockito substitute was a
worse fix than digging one level further. The actual, resolvable questions ("does this
artifact carry this class, and at what package") don't require guessing even for an
unfamiliar/relocated API — the real jars are sitting on Maven Central:

```bash
curl -s ".../spring-boot-starter-webmvc-test/4.1.1/spring-boot-starter-webmvc-test-4.1.1.pom" | grep artifactId
curl -sL ".../spring-boot-webmvc-test/4.1.1/spring-boot-webmvc-test-4.1.1.jar" -o x.jar && unzip -l x.jar | grep WebMvcTest.class
```

That found the real answer in minutes: `@WebMvcTest` had moved to
`org.springframework.boot.webmvc.test.autoconfigure` (from
`org.springframework.boot.test.autoconfigure.web.servlet`), and — the genuinely interesting
find — **Jackson itself had relocated**, `com.fasterxml.jackson.databind` →
`tools.jackson.databind` under groupId `tools.jackson.core`, which tracks Jackson's real,
publicly-discussed plan for its 3.x line. `MockMvc`, `MockitoBean`,
`MockMvcRequestBuilders`, and `MockMvcResultMatchers` were all exactly where 20 years of
Spring muscle memory expects them — only the two things that actually moved needed
updating. **The general shape:** "I can't find the right import" is a research task with a
concrete, checkable answer (inspect the actual dependency's POM/jar), not a signal to
downgrade the test's ambition.

## The twelfth lesson (corrected): don't let a plausible one-off theory become a documented fact — verify it, especially the theory that explains itself away

A build failed at the image-push step with `lstat ... target/demo-...jar: no such file or
directory`, despite `maven-build`'s own logs showing that exact jar built seconds earlier in
the same workspace. A retry succeeded. **The first write-up of this lesson concluded**
`local-path-provisioner`'s node-local storage was the cause — a shared Tekton workspace
only really shared if the scheduler happened to co-locate every Task on one node, which
"nothing enforced." That was never actually verified against the failing run (its pods were
already gone by the time it was investigated) — it was inferred from the retry succeeding
on one node, which is exactly the "plausible conclusion, never checked against evidence"
failure mode from the eleventh lesson, caught this time only because a real fix was
attempted next.

Building an explicit pod-affinity fix to force co-scheduling **immediately failed**, and
the failure was the correction: Tekton already ships an **Affinity Assistant**
(`coschedule: workspaces`, on by default in this install) built for exactly this problem —
it creates a dedicated assistant pod first, then gives every Task pod sharing that
workspace a *required* affinity to that specific pod (sidestepping the "first task has
nothing to match yet" problem a naive fix hits). Confirmed live: an
`affinity-assistant-<hash>` StatefulSet gets created per `PipelineRun`, and every Task pod
carries a required `podAffinity` referencing it by its stable instance label — meaning
co-scheduling was *already guaranteed*, correctly, the entire time. The original flake's
real cause is still unknown; the node-mismatch theory that felt right is now known to be
wrong. Left open, honestly, rather than replaced with a new unverified guess.

**The general shape:** the moment a plausible diagnosis is about to become a documented
lesson — not just a private hunch — is exactly the moment it deserves the most scrutiny,
because a wrong lesson written down confidently is more damaging than an admitted unknown.
Attempting the actual fix is often the fastest way to find out a diagnosis was wrong: the
fix broke immediately, on contact with a mechanism that shouldn't have allowed the original
symptom to happen at all.

**Final update — the real cause, found by finally checking the thing that mattered:**
correlating every failed build's start/completion timestamps against every *other*
`PipelineRun`'s showed a perfect pattern — every single failure this project ever hit
overlapped, within seconds, with a second `PipelineRun` also running. The confirming
evidence was in the losing run's own logs the whole time: the later run's `git-clone`
cleanup step (`cleandir()`) does `rm -rf .../pom.xml .../src .../target` on the *shared*
`tekton-workspace` PVC — deleting the earlier run's files out from under it, mid-build, if
both happen to be active at once. `tekton-workspace` is one fixed-name PVC reused by every
run (kept deliberately, for the Maven `.m2` cache), with **zero mutual exclusion** between
runs — the CronJob's own `concurrencyPolicy: Forbid` only prevents its *trigger-check* job
from overlapping itself, not the actual `PipelineRun`s it creates from overlapping with each
other or with a manually-triggered one. In practice, this fired constantly during this
project specifically because pushing a commit and then immediately triggering a manual
verification build — the normal workflow of this entire session — is exactly the condition
that makes the CronJob's own auto-trigger and a manual trigger race each other.

Fixed by having the trigger check for any non-terminal `build-app` `PipelineRun` before
creating a new one, skipping the cycle (the next poll picks the same commit back up) rather
than racing a build already in flight. This protects the *automated* path completely; it
does not (and isn't meant to) stop a human from manually triggering a second build while one
is already running — that remained a real way to reproduce the bug on demand once the cause
was known, and was used to verify the fix.

Three theories, in order, each retired only once contradicted by direct evidence rather than
by a better guess: node-locality (never checked, wrong) → "Tekton's Affinity Assistant
already prevents that, so the cause is unknown" (correctly ruled out one theory, honestly
declined to invent another) → the actual mechanism, found by finally checking run-to-run
timing instead of re-theorizing about storage. The middle step — admitting "I don't know
yet" instead of reaching for the next plausible-sounding story — is what kept the eventual
answer from being a fourth wrong guess.
