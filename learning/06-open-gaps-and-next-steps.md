# Open gaps and next steps

The honest state at the end of this pass: the platform is built, but a few pieces were
still manual or uncommitted, so a fresh cluster did not yet rebuild everything from Git.
This file is the punch list for closing that gap, which is what "immutable and
repeatable" actually means in practice, not just in theory.

## Resolved earlier: demo-app stuck in CrashLoopBackOff

The root cause turned out to be a chain of four separate problems, found one after
another:

1. The image reference was a bare name, `demo-app:1.0`, which defaults to Docker Hub
   instead of the local registry. Fixed by using the full reference,
   `registry-local:5000/demo-app:1.0`.
2. The nodes could not actually pull from that registry, because they expected HTTPS and
   the local registry only serves plain HTTP. Fixed with the insecure-registry node
   configuration described in `04-solutions-reference.md`.
3. The image that did get deployed was built with a broken layered jar setup, and failed
   at startup with a `JarLauncher` ClassNotFound error. Fixed by changing the Dockerfile
   to a plain `java -jar`, then rebuilding through Tekton, which succeeded.
4. The actual final cause: the fix from step 3 never reached the running pod at all.
   `registry-local` had quietly lost every image it had stored, since it had no
   persistent volume and had been restarted at some point (confirmed with a direct `404`
   against its own manifest API), and separately, a worker node still had the old, broken
   image cached locally under that same mutable tag, which `imagePullPolicy:
   IfNotPresent` kept serving forever regardless of what the registry now had. Fixed by
   deleting the node's cached image with `crictl rmi`, switching to
   `imagePullPolicy: Always`, and re-running the pipeline under the correct
   `tekton-build` ServiceAccount (an earlier retry under the default ServiceAccount had
   failed with a git authentication error, described in `04-solutions-reference.md`).
   After that, `demo-app` reached `1/1 Running`, connected to Postgres, and served
   traffic normally.

The full trail is in
[03-debugging-playbook.md](03-debugging-playbook.md#a-pipeline-succeeded-but-the-pod-still-runs-the-old-broken-build)
and
[04-solutions-reference.md](04-solutions-reference.md#a-stale-image-getting-served-despite-a-successful-rebuild).

This surfaced a new gap worth naming: the `imagePullPolicy: Always` fix was applied
directly with `kubectl patch` against the live cluster. Since this Deployment is
Flux-managed, that change was not durable on its own. It also needed to be committed
into the actual manifest in `flux-infra`, or the next reconciliation would have reverted
it. It was also worth giving `registry-local` an actual persistent volume, or at least
confirming the one it was supposed to have was really mounted, so this exact class of
failure could not happen again after the next restart.

## Update: the gap-closing pass happened, with Claude Code

Everything in the table below is now closed, all done in one Claude Code session:

| Gap | Status |
|---|---|
| `tekton-build` ServiceAccount | Committed, added to `tekton-pipeline/kustomization.yaml`. |
| `tekton-workspace` PVC | Committed, in the same kustomization. |
| `registry-local` Service and EndpointSlice | Committed to `infrastructure/registry-local.yaml`. |
| `git-credentials` secret | SOPS-encrypted, committed to `tekton-pipeline/git-credentials.yaml`. |
| `postgres-secret` | SOPS-encrypted, committed to `apps/postgres-secret.yaml`. This had previously been plaintext inside `demo-app.yaml`. That exposure predates this fix; the credentials were low-stakes development values, but it is worth knowing it once sat in git history in plain text. |
| containerd insecure-registry configuration | Turned out to already be done, through `kind-cluster.yaml` plus a `registry-config/` directory covering all five mirrors. It just existed only as untracked files on disk. Now declared properly in `nix-config` (`home.file`), symlinked from the Nix store. |
| `setcap` on `newuidmap` and `newgidmap` | Already present in `nix-config/install.sh` from an earlier session. |

One wrinkle around `registry-local`'s IP address is not fully closed. The committed
EndpointSlice hardcodes the container's current IP address (`10.4.1.33`). If
`registry-local` is ever recreated without also passing `--ip 10.4.1.33` again (see
`04-solutions-reference.md`), the EndpointSlice will end up pointing at a dead address
once more. Turning this into a real fixed assignment, or a small controller that keeps
it in sync automatically, is still open.

Concretely, here is what was actually done for SOPS, not just planned: generated an age
keypair at `~/.config/sops/age/keys.txt` (the private key is not in git), loaded it into
the cluster as the `sops-age` secret in the `flux-system` namespace, added a `.sops.yaml`
file with `encrypted_regex: ^(data|stringData)$`, encrypted both secrets in place, and
added `spec.decryption` to the `apps` and `tekton-pipeline` Flux `Kustomization` objects.
This was verified by re-running the full Tekton pipeline, which authenticated to GitHub
using the SOPS-decrypted `git-credentials` secret and succeeded.

Also resolved: the old exposed personal access token was rotated using `gh auth refresh`
through its device-code flow, rather than through the GitHub web UI. See the fourth
lesson in the root `README.md`. The previous token is now unused, though revoking it
outright still needed one manual step on GitHub's website, since it had not originally
been issued through `gh`'s own OAuth app (see the credential-rotation entry in
`04-solutions-reference.md`).

## Resolved: the acid test

This was actually run for real, not just described: `kind delete cluster`, then
`kind create --config ~/kind-cluster.yaml`, then `flux bootstrap github`, then
re-provisioning the `sops-age` Secret from the local key file by hand. All six
Kustomizations reached Ready, and both `demo-app` and `postgres` came up `1/1 Running`,
from Git alone.

It did not pass on the first try. Making `registry-config` declarative through
home-manager, a fix from earlier in this same session, turned its files into symlinks
pointing into `/nix/store`, and `kind`'s `extraMounts` did not expose that path inside
the node containers. That meant the freshly created nodes could not read the
insecure-registry `hosts.toml` file, so `demo-app` went straight to `ImagePullBackOff`
with the same HTTPS-versus-HTTP error as before, even though everything else about the
cluster was correct. Fixed by also bind-mounting `/nix/store`, read-only, into every node
in `kind-cluster.yaml`. See the third lesson in `README.md` for the full story. The
lesson that matters going forward is to re-run the acid test after any change to the
reproducibility path itself, not just once at the very end of a project.

One asterisk remains permanently: the `sops-age` Secret can never live in Git, since
storing it there would defeat the entire purpose of encrypting secrets with it. So "zero
manual steps" really means "zero manual steps, plus re-loading the one decryption key a
human has to keep safe somewhere else." That is expected behavior, not a gap left to
close.

## Resolved: production hardening, round one, health probes and resource limits

`demo-app` and `postgres` had no liveness or readiness probes, and no resource requests
or limits, for the entire build up to this point. A pod with neither of those just shows
as `Running` forever, regardless of whether it is actually serving traffic or about to
run the node out of memory. Fixed by adding:

- `spring-boot-starter-actuator`, plus
  `management.endpoint.health.probes.enabled=true`, to the app itself in the
  `springboot-demo` repo, giving it real `/actuator/health/liveness` and
  `/actuator/health/readiness` endpoints. Plain Spring Boot has no health endpoint at all
  without this.
- `readinessProbe`, `livenessProbe`, and `resources.requests`/`resources.limits` on both
  containers in `flux-infra/clusters/kind/apps/demo-app.yaml`.

See the fifth lesson in `README.md` for the one-time restart this caused, which was a
probe racing Postgres's very first `initdb` run rather than an actual misconfiguration,
along with the exact values used.

## Resolved: production hardening, round two, CI/CD maturity and Flux image automation

The pipeline used to need a manual `kubectl create -f pipelinerun` for every build, and it
always pushed the same fixed `:1.0` tag. Now:

- Every build gets a unique tag, `<committer-epoch>-<full-sha>`, computed at the Pipeline
  level from results the `git-clone` Task already produces, so no new Task was needed.
- A pull-based trigger, a `CronJob`, polls `git ls-remote` against the source repo every
  two minutes and creates a `PipelineRun` whenever it sees a new commit. A real webhook is
  not possible here, since there is no inbound path through WSL's NAT, as explained in
  `02-what-went-badly.md`.
- Flux image automation, made up of an `ImageRepository`, an `ImagePolicy`, and an
  `ImageUpdateAutomation`, watches the registry and automatically commits the
  Deployment's updated tag back into `flux-infra`.

This was verified with a real end-to-end test: an actual code change was pushed, and with
nothing else touched by hand, it was watched flowing through the CronJob, then Tekton,
then the registry, then Flux's scan, policy, and automation steps, and finally a redeploy,
after which the new code was confirmed to actually be serving traffic. See the sixth and
seventh lessons in `README.md` for what broke along the way, including a Flux API version
mismatch, the deploy key being read-only by default, a genuinely tricky cross-namespace
DNS issue, and a runaway-trigger bug caused by a fail-open fallback in the CronJob's
script. See `04-solutions-reference.md` for the copy-paste fixes themselves.

## Resolved: production hardening, round three, security and reliability

These were deliberately chosen as the lighter items to tackle before observability, which
needed a much heavier `kube-prometheus-stack` footprint to add. On the security side:

- A default-deny `NetworkPolicy` in the `default` namespace, with only explicit allows
  layered on top: `demo-app` is reachable only from the Gateway, and `postgres` is
  reachable only from `demo-app` and the backup job. This was verified against the
  cluster's actual networking plugin, `kindnet`, with a throwaway deny-all test before
  any real policy was trusted. See the ninth lesson in `README.md`.
- `demo-app` and `postgres` now both run as non-root. Both had actually been running as
  root, unnoticed, for the entire build up to this point, described in the eighth lesson
  in `README.md`. Postgres needed a one-time `initContainer` to `chown` a volume that
  already had root-owned data on it.
- `automountServiceAccountToken` is set to false on both, since neither one ever calls
  the Kubernetes API.

On the reliability side:

- `demo-app` now runs two replicas behind a PodDisruptionBudget.
- `postgres` stays single-replica, since no real replication was set up, but it now has
  its own PodDisruptionBudget with `maxUnavailable: 0`, protecting it from an accidental
  voluntary eviction.
- A daily `pg_dump` CronJob writes to a dedicated PVC, keeping the seven most recent
  dumps. This caught a real bug the first time it actually ran, not just from reading the
  manifest: `pg_dump` specifically needs a variable named `PGPASSWORD`, which the
  secret-key-name environment variables that `envFrom` provides do not satisfy on their
  own.

Deliberately left for later: TLS on the Gateway, and the Envoy Gateway API-gateway
features, namely `SecurityPolicy` for authentication and `BackendTrafficPolicy` for rate
limiting, which came up from a clarifying question during this round about the
difference between the Gateway API and an API gateway. See the "What was built" section
of `README.md` for that distinction.

## Resolved: production hardening, round four, observability and real app code

`kube-prometheus-stack`, bundling Prometheus, Grafana, Alertmanager, node-exporter, and
kube-state-metrics, was installed through Flux in its own namespace and its own
Kustomization layer, with a separate dependent layer for the `ServiceMonitor`, following
the same "the custom resource type must exist first" pattern used for MetalLB and image
automation. `demo-app` now exposes real Micrometer and JVM metrics through
`/actuator/prometheus`. Getting there hit, and then fixed, a genuinely subtle bug with no
error message anywhere: a `ServiceMonitor` matches a Service's `metadata.labels`, not its
`spec.selector`, so the scrape target was silently dropped and never even showed up as
"down" anywhere. This round also had to extend the previous round's default-deny
`NetworkPolicy` to allow the `monitoring` namespace through, or scraping would have been
blocked the exact same silent way. See the tenth lesson in `README.md`.

Alongside this, the app itself got real domain logic for the first time. The existing
`Message` CRUD stub, a JPA entity exposed directly over REST with no service layer and no
tests, was refactored into a proper layered Controller, Service, and Repository
structure with DTOs, a not-found exception handled through
`@RestControllerAdvice`, and both unit tests and a real `MockMvc` slice test. This round
also removed `-DskipTests` from the CI pipeline, since tests that never actually run in
CI are not worth much. Getting the `MockMvc` test working surfaced a genuinely
interesting finding: two real class relocations in this Spring Boot version. `@WebMvcTest`
had moved packages, and Jackson itself had relocated from `com.fasterxml.jackson` to
`tools.jackson`, matching its real, publicly planned 3.x migration. Both were found by
inspecting the actual published jars on Maven Central rather than settling for a weaker
test that avoided the question.

One more thing surfaced along the way, and it is worth recording as a correction rather
than a gap. A `PipelineRun` failed once, and the failure was initially attributed to a
`local-path`-backed Tekton workspace not being reliably shared across nodes. Attempting
the actual fix, a custom pod affinity rule, disproved that theory almost immediately,
since Tekton already ships a built-in Affinity Assistant that guarantees exactly this
kind of co-scheduling by design, confirmed live on the running cluster. The original
failure's real cause was still unknown at that point, and the plausible-sounding
diagnosis was retracted rather than left standing as a documented lesson. See the
corrected twelfth lesson in `README.md` for what the actual cause turned out to be.
Nothing needed fixing here beyond that correction. The hand-rolled affinity attempt was
reverted.

## Now open: making it more production-shaped, round five onward

The remaining layers, roughly in priority order:

1. Health probes and resource limits: done, round one.
2. CI/CD maturity and Flux image automation: done, round two.
3. Security and reliability, meaning NetworkPolicies, non-root, RBAC, PodDisruptionBudgets,
   and backups: done, round three.
4. Observability and real app code with tests: done, round four, above.
5. TLS on the Gateway. This needs cert-manager. A self-signed or internal certificate
   authority is enough for a homelab; a real certificate would only matter if this were
   ever exposed beyond the local network.
6. API-gateway features on top of the existing Gateway API setup, meaning rate limiting
   and authentication through Envoy Gateway's own `SecurityPolicy` and
   `BackendTrafficPolicy` custom resources, rather than a separate product. Nothing new
   needs to be installed here, just configuration of what is already running.

Lower-priority housekeeping still open:

- The `registry-local` dynamic-IP wrinkle described above. The committed EndpointSlice
  hardcodes the container's current IP address (`10.4.1.33`). If the container is ever
  recreated without also passing `--ip 10.4.1.33` again (see
  `04-solutions-reference.md`), the EndpointSlice breaks the same way once more. A proper
  static IP assignment, or a small reconcile loop, would close this for good.
- Tekton PipelineRun accumulation. Completed runs are not garbage collected
  automatically, and this got noticeably worse once builds started triggering
  automatically on every change to the source repo, on top of a roughly 35-minute
  runaway-trigger bug that alone produced about 30 extra runs in one sitting, described
  in the seventh lesson in `README.md`. Setting `ttlSecondsAfterFinished`, or running a
  periodic prune, is now a real need rather than a theoretical one.
- The Tekton build-push flake is resolved, for real this time. The final cause: two
  `PipelineRun`s sharing one fixed-name `tekton-workspace` PVC with no mutual exclusion
  between them, so a later run's `git-clone` cleanup step deletes an earlier run's
  `pom.xml`, `src`, and `target` directories mid-build if both happen to be running at
  once. This was confirmed by correlating every failed run's timestamps against every
  other run's, not by theorizing further about it. The fix: the trigger `CronJob` now
  checks for a non-terminal `build-app` run before creating a new one. This does not
  protect against someone manually triggering a second build while one is already
  running, which is a testing artifact rather than a flaw in the automated path. See the
  twelfth lesson's final update in `README.md` for the complete three-theories story.

## Update: this punch list was closed with Claude Code

Everything above marked "Resolved," meaning the gap-closing pass, the acid test
including diagnosing and fixing the `/nix/store` symlink bug it exposed, the credential
rotation, and rounds one through four of production hardening (probes and resources; CI/CD
and image automation; security and reliability; observability and real app code with
tests), was done across Claude Code sessions in `~/projects/flux-infra` and the app repo,
working directly against the live cluster and both git-based repositories. It ended up
being exactly the investigate, fix, verify loop this section originally predicted it
would be: no copy-paste latency between finding a problem and testing a fix, direct
`kubectl`, `flux`, `sops`, `gh`, and `curl`-against-Maven-Central access the whole way
through, and every fix verified live rather than assumed. That meant rerunning the
pipeline, re-checking pod health, re-running the acid test, pushing a real commit and
watching the automated loop react to it on its own, empirically testing a security
control before trusting it, actually running the backup job once rather than just reading
its manifest, querying Prometheus's own generated configuration to find a silently
dropped scrape target, downloading and inspecting real jars instead of guessing at a
relocated API, and, just as importantly, retracting a plausible diagnosis the moment the
real fix disproved it rather than letting it stand as a documented lesson. The "round
five onward" list above is the natural next hand-off for that same workflow.

## The one lesson to carry forward above all the others

Before calling a platform reproducible, actually run the acid test: destroy it and
rebuild it from scratch. Every single gap listed above was invisible right up until a
fresh cluster exposed it. "It works" and "it rebuilds from Git" are two different claims,
and only the second one was ever the actual goal here.
