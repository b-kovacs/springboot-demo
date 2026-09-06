# Open Gaps & Next Steps

The honest state at the end of the session: the platform is built, but a few pieces are
still manual/uncommitted, so a fresh cluster does **not** yet rebuild *everything* from
Git. This is the punch-list to close that — the real "immutable & repeatable" finish.

## Resolved since last session: demo-app CrashLoopBackOff

Root cause chain, in order of discovery:
1. Image had a bare name (`demo-app:1.0`) → fixed to `registry-local:5000/demo-app:1.0`.
2. Nodes couldn't pull (HTTPS-vs-HTTP) → fixed with the insecure-registry node patch.
3. Deployed image was a broken **layered jar** (`JarLauncher` ClassNotFound) → fixed the
   Dockerfile to plain `java -jar`, and rebuilt via Tekton (build succeeded).
4. **The actual final cause:** the fix in step 3 never reached the running pod. `registry-local`
   had lost its stored images (no persistent volume, restarted at some point — confirmed via
   a direct `404` on its manifest API), and a worker node still had the old broken image
   cached under the same mutable tag, which `imagePullPolicy: IfNotPresent` kept serving
   forever. Fixed by deleting the node's cached image (`crictl rmi`), setting
   `imagePullPolicy: Always`, and re-running the pipeline with the correct
   `tekton-build` ServiceAccount (a first retry using the `default` SA failed with a git
   auth error — see [04-solutions-reference.md](04-solutions-reference.md)).
   `demo-app` is now `1/1 Running`, connected to Postgres, serving traffic.

Full trail: [03-debugging-playbook.md](03-debugging-playbook.md#a-pipeline-succeeded-but-the-pod-still-runs-the-old-broken-build) ·
[04-solutions-reference.md](04-solutions-reference.md#stale-image-served-from-cache-despite-a-successful-rebuild).

**New gap this surfaced:** the `imagePullPolicy: Always` fix was applied with `kubectl
patch` directly against the live cluster. If this Deployment is Flux-managed, that change
is not durable — it needs to also be committed into the manifest in `flux-infra`, or the
next reconciliation reverts it. Also worth doing: give `registry-local` a persistent
volume (or confirm the one it's supposed to have is actually mounted) so this class of
failure can't recur on the next restart.

## Update: the gap-closing pass happened (with Claude Code)

Everything in the table below is now **closed**, done in one Claude Code session:

| Gap | Status |
|---|---|
| `tekton-build` ServiceAccount | ✅ committed, added to `tekton-pipeline/kustomization.yaml` |
| `tekton-workspace` PVC | ✅ committed, same kustomization |
| `registry-local` Service + EndpointSlice | ✅ committed to `infrastructure/registry-local.yaml` |
| `git-credentials` secret | ✅ SOPS-encrypted, committed to `tekton-pipeline/git-credentials.yaml` |
| `postgres-secret` | ✅ SOPS-encrypted, committed to `apps/postgres-secret.yaml` (was previously **plaintext** in `demo-app.yaml` — that exposure predates this fix; low-stakes dev creds, but worth knowing it was in git history) |
| containerd insecure-registry config | ✅ turned out to already be done (`kind-cluster.yaml` + a `registry-config/` dir with all 5 mirrors) — it just lived as untracked files on disk. Now declared in `nix-config` (`home.file`), symlinked from the Nix store |
| `setcap` on newuidmap/newgidmap | already in `nix-config/install.sh` from an earlier session |

**registry-local dynamic-IP wrinkle:** not fully closed. The committed EndpointSlice
hardcodes the container's current IP (`10.4.1.33`). If `registry-local` is ever recreated
without `--ip 10.4.1.33` (see `04-solutions-reference.md`), the EndpointSlice will point at
a dead address again. Turning this into an actual fixed assignment (or a small reconcile
loop) is still open.

**SOPS, concretely (what was actually done, not the plan):** generated an age keypair
(`~/.config/sops/age/keys.txt`, private key **not in git**), loaded it into the cluster as
`sops-age` in `flux-system`, added `.sops.yaml` with `encrypted_regex: ^(data|stringData)$`,
encrypted both secrets in place, and added `spec.decryption` to the `apps` and
`tekton-pipeline` Flux `Kustomization` objects. Verified by re-running the full Tekton
pipeline — it authenticated to GitHub using the SOPS-decrypted `git-credentials` and
succeeded.

**Resolved:** the old PAT was rotated via `gh auth refresh` (device-code flow) rather than
the GitHub web UI — see the fourth lesson in the root `README.md`. The previous token is
now unused (though it required a one-time manual step to actually revoke it on GitHub,
since it wasn't `gh`'s own token — see below).

## Resolved: the acid test

Ran it for real: `kind delete cluster` → `kind create --config ~/kind-cluster.yaml` →
`flux bootstrap github` → re-provision the `sops-age` Secret from the local key file. All
6 Kustomizations reached Ready and both `demo-app` and `postgres` came up `1/1 Running`
from Git alone.

It did **not** pass on the first try. Making `registry-config` declarative via
home-manager (this same session's fix) turned its files into symlinks into `/nix/store`,
which kind's `extraMounts` didn't expose inside the node containers — so the fresh nodes
couldn't read the insecure-registry `hosts.toml` and `demo-app` went `ImagePullBackOff`
with the HTTPS-vs-HTTP error, on an otherwise-correct cluster. Fixed by also bind-mounting
`/nix/store` (read-only) into every node in `kind-cluster.yaml`. See the third lesson in
`README.md` for the full story — the takeaway that matters going forward is **re-run the
acid test after any change to the reproducibility path itself**, not just once at the end.

**One asterisk, permanently:** the `sops-age` Secret can never be in Git (that would defeat
SOPS), so "zero manual steps" really means "zero manual steps, plus re-loading the one
decryption key a human has to keep safe elsewhere." That's expected, not a gap to close.

## Resolved: production-hardening, round one — health probes + resource limits

`demo-app` and `postgres` had no liveness/readiness probes and no resource requests/limits
for the entire build up to this point — a pod with neither just shows `Running` forever
regardless of whether it's actually serving traffic or about to OOM the node. Added:
- `spring-boot-starter-actuator` + `management.endpoint.health.probes.enabled=true` to the
  app (`springboot-demo` repo) for real `/actuator/health/liveness` and `/readiness`
  endpoints — Spring Boot has no health endpoint without this.
- `readinessProbe`/`livenessProbe` + `resources.requests/limits` on both containers in
  `flux-infra/clusters/kind/apps/demo-app.yaml`.

See the fifth lesson in `README.md` for the one-time restart this caused (a probe racing
Postgres's first `initdb` — expected, not a misconfiguration) and the exact values used.

## Resolved: production-hardening, round two — CI/CD maturity + Flux image automation

The pipeline used to need a manual `kubectl create -f pipelinerun` and pushed a fixed
`:1.0` tag. Now:
- **Unique tags** — `<committer-epoch>-<full-sha>`, computed at the Pipeline level from
  `git-clone`'s existing `commit`/`committer-date` Task results (no new Task needed).
- **A pull-based trigger** — a `CronJob` polls `git ls-remote` on the source repo every 2
  minutes and creates a `PipelineRun` on a new commit. A real webhook is impossible here
  (see `02-what-went-badly.md` — no inbound path through WSL/NAT).
- **Flux image automation** — `ImageRepository` + `ImagePolicy` + `ImageUpdateAutomation`,
  watching the registry and auto-committing the Deployment's tag into `flux-infra`.

Verified with a real end-to-end test: pushed an actual code change, and — untouched —
watched it flow through the CronJob, Tekton, the registry, Flux's scan/policy/automation,
and a redeploy, then confirmed the new code was actually serving traffic. See the sixth and
seventh lessons in `README.md` for what broke along the way (a Flux API version mismatch,
the deploy key being read-only by default, a genuinely tricky cross-namespace DNS issue,
and a runaway-trigger bug from a fail-open fallback in the CronJob script) and
`04-solutions-reference.md` for the copy-paste fixes.

## Resolved: production-hardening, round three — security + reliability

Chosen deliberately as the *lighter* items before observability (which needs a heavier
`kube-prometheus-stack` footprint). Security:
- Default-deny `NetworkPolicy` in `default` namespace, explicit allows only:
  `demo-app` reachable solely from the Gateway, `postgres` solely from `demo-app` and the
  backup job. **Verified `kindnet` actually enforces this** with a throwaway deny-all test
  before writing real policies — see the ninth lesson in `README.md`.
- `demo-app` and `postgres` now run as non-root (both had been running as root, unnoticed,
  the entire build — eighth lesson in `README.md`). Postgres needed a one-time
  `initContainer` to `chown` its already-root-owned volume.
- `automountServiceAccountToken: false` on both — neither calls the k8s API.

Reliability:
- `demo-app`: 2 replicas + a PodDisruptionBudget.
- `postgres`: stays single-replica (no replication set up), but has its own PDB
  (`maxUnavailable: 0`) against accidental voluntary eviction.
- A daily `pg_dump` CronJob to a dedicated PVC, retaining the 7 most recent dumps. Caught a
  real bug on the first actual run (not from reading the manifest): `pg_dump` needs
  `PGPASSWORD` specifically, which `envFrom`'s secret-key-name env vars don't provide.

**Not done, deliberately deferred:** TLS on the Gateway, and the Envoy Gateway API-gateway
features (`SecurityPolicy` for auth, `BackendTrafficPolicy` for rate limiting) that came up
from a clarifying question about Gateway API vs. API Gateway during this round — see
`README.md`'s "What was built" for that distinction.

## Resolved: production-hardening, round four — observability + real app code

`kube-prometheus-stack` (Prometheus, Grafana, Alertmanager, node-exporter,
kube-state-metrics) via Flux, own namespace/Kustomization layer, dependent config layer for
the `ServiceMonitor` (same CRD-must-exist-first pattern as MetalLB/image-automation).
`demo-app` now exposes real Micrometer/JVM metrics via `/actuator/prometheus`. Hit and fixed
a genuinely subtle, error-free bug getting there: a `ServiceMonitor` matches a Service's
`metadata.labels`, not its `spec.selector` — the target was silently dropped, never even
shown as "down." Also had to extend the previous round's default-deny `NetworkPolicy` to
allow the `monitoring` namespace through, or scraping would have been silently blocked the
same way. See the tenth lesson in `README.md`.

Alongside this, the app itself got real domain logic for the first time: the existing
`Message` CRUD stub (a JPA entity exposed directly over REST, no service layer, no tests)
was refactored into a proper layered Controller → Service → Repository with DTOs, a
not-found exception + `@RestControllerAdvice`, and both unit tests and a real `MockMvc`
slice test — plus removing `-DskipTests` from the CI pipeline, since tests that never run in
CI aren't worth much. Getting the `MockMvc` test working surfaced a genuinely interesting
finding: two real class relocations in this Spring Boot version (`@WebMvcTest` moved
packages; Jackson itself relocated from `com.fasterxml.jackson` to `tools.jackson`, its real
publicly-planned 3.x migration) — found by inspecting the actual Maven Central jars rather
than settling for a weaker test. See the eleventh lesson in `README.md`.

One more thing surfaced along the way, and it's a correction, not a gap: a `PipelineRun`
failed once, initially attributed to a `local-path`-backed Tekton workspace not being
reliably shared across nodes. Attempting the actual fix (custom pod affinity) disproved
that theory immediately — Tekton already ships a built-in Affinity Assistant that
guarantees this exact co-scheduling by design, confirmed live. The original failure's real
cause is unknown; the plausible-sounding diagnosis was retracted rather than left on
record. See the corrected twelfth lesson in `README.md`. Nothing to fix here — the
hand-rolled attempt was reverted.

## Now open: making it more "production," round five onward

The remaining layers, roughly in priority order:

1. ~~Health probes + resource limits~~ — done (round one).
2. ~~CI/CD maturity + Flux image automation~~ — done (round two).
3. ~~Security + reliability (NetworkPolicies, non-root, RBAC, PDBs, backups)~~ — done (round three).
4. ~~Observability + real app code/tests~~ — done (round four, above).
5. **TLS on the Gateway** — needs cert-manager (a self-signed or internal CA is enough for
   a homelab; a real cert only matters if this is ever exposed beyond the LAN).
6. **API-gateway features on the existing Gateway API setup** — rate limiting and/or auth
   via Envoy Gateway's `SecurityPolicy`/`BackendTrafficPolicy` CRDs, rather than a separate
   product; nothing new to install, just configure what's already running.

Lower-priority housekeeping:
- **`registry-local` dynamic-IP wrinkle** — still open. The committed EndpointSlice
  hardcodes the container's current IP (`10.4.1.33`); if the container is ever recreated
  without `--ip 10.4.1.33` (see `04-solutions-reference.md`), the EndpointSlice breaks
  again. A static IP assignment or a small reconcile loop would close this properly.
- **Tekton PipelineRun accumulation.** Completed runs aren't garbage collected
  automatically, and this got noticeably worse once builds trigger automatically every time
  the source repo changes (plus a ~35-minute runaway-trigger bug produced ~30 extra runs in
  one sitting — see the seventh lesson in `README.md`). A `ttlSecondsAfterFinished` or a
  periodic prune is now a real, not just theoretical, need.
- ~~The Tekton build-push flake~~ — **resolved, for real this time.** Final cause: two
  `PipelineRun`s sharing the one fixed-name `tekton-workspace` PVC with no mutual
  exclusion — a later run's `git-clone` cleanup deletes an earlier run's `pom.xml`/`src`/
  `target` mid-build if both are active at once. Confirmed by correlating every failed
  run's timestamps against every other run's, not by theorizing further. Fixed: the
  trigger `CronJob` now checks for a non-terminal `build-app` run before creating a new
  one. Does not protect against manually triggering a second build while one is already
  running (a testing artifact, not an automated-path flaw) — see the twelfth lesson's
  final update in `README.md` for the full three-theories story.

## Update: this punch-list was closed with Claude Code

Everything above marked "Resolved" — the gap-closing pass, the acid test (including
diagnosing and fixing the `/nix/store` symlink bug it exposed), the credential rotation,
and rounds one through four of production-hardening (probes/resources; CI/CD + image
automation; security + reliability; observability + real app code/tests) — was done in
Claude Code sessions in `~/projects/flux-infra` and the app repo, working directly against
the live cluster and both git-based repos. It was, in fact, exactly the
investigate→fix→verify loop this section originally predicted it would be: no copy-paste
latency, direct `kubectl`/`flux`/`sops`/`gh`/`curl`-against-Maven-Central access, and each
fix verified live (rerun the pipeline, re-check pod health, re-run the acid test, push a
real commit and watch the automated loop react on its own, empirically test a security
control before trusting it, actually run the backup job, query Prometheus's own generated
config to find a silent scrape-target drop, download and inspect real jars rather than
guess at a relocated API, and — just as importantly — retract a plausible diagnosis the
moment the actual fix disproved it, rather than let it stand as a documented lesson)
rather than assumed. The "round five onward" list above is the natural next hand-off for
the same workflow.

## Meta-lesson to carry forward

Before considering the platform "reproducible," run the acid test: **destroy and rebuild
from scratch.** Every gap above was invisible until a fresh cluster exposed it. "It works"
and "it rebuilds from Git" are different claims — only the second one is the goal here.
