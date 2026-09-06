# What Went Badly

The friction, dead-ends, and (mostly environmental) walls that cost the most time —
and what to do differently.

## The recurring theme: WSL + rootless networking

The single biggest source of pain. Running rootless containers inside WSL2 means there is
**no clean host↔container↔outside network path**, and this wall was hit over and over:

- **MetalLB LoadBalancer IPs** were assigned correctly but **not routable from the WSL
  host** (only reachable from inside the cluster). The load balancer "worked" but you
  couldn't reach it from your browser.
- **Gateway access** ultimately needed `kubectl port-forward` because no inbound path
  existed.
- **Mirrored networking mode** (tried to make Windows↔WSL localhost seamless) **broke
  loopback *inside* WSL** — `127.0.0.1` connections got refused even with a server bound
  and listening. Reverted to NAT mode.
- **Webhook-based automation** (Tekton Triggers, Argo Events, GitHub webhooks) is
  fundamentally impossible without a tunnel, because GitHub can't reach a laptop behind
  NAT/WSL.

**Takeaway:** rootless-kind-on-WSL is excellent for *learning* but fights you on anything
requiring inbound or host-reachable networking. For always-on / externally-reachable
needs, a real Linux VM or cloud cluster is the right tool. Pull-based patterns (Flux
polling, self-hosted runners that poll outbound) are the WSL-friendly workarounds.

## Things that don't survive a WSL restart / cluster recreation

kind clusters are **ephemeral by nature** — a `wsl --shutdown` leaves node containers in a
broken `Created` state with a dead API server. Recreation, not resume, is the workflow.
Worse, several things had to be re-done manually after every recreate because they were
never committed to Git:

- `tekton-build` ServiceAccount + `git-credentials` secret
- `registry-local` Service + EndpointSlice (for in-cluster DNS resolution)
- The containerd insecure-registry node patch (so nodes can *pull* from the local registry)
- `postgres-secret`
- File capabilities (`setcap` on `newuidmap`/`newgidmap`) don't persist across WSL restarts

**This was the deepest lesson, learned the hard way:** every one of these is a thing that
*should* be in Git/`kind-cluster.yaml`, and each one blocked progress until recreated.
"It worked before" is meaningless if "before" was a manual step on a now-deleted cluster.

## Cold-start slowness masquerading as failure

On a fresh cluster, **every image pulls from the internet**, and heavy stacks (MetalLB's
FRR pods, Tekton, Envoy) took several minutes each. This repeatedly *looked* like a hang
or a failure (Flux Kustomization `timeout`, `context deadline exceeded`, pods stuck in
`ContainerCreating`) when it was just slow. Time was lost both waiting and, worse,
intervening on things that would have converged on their own.

## The layered-jar optimization was a rabbit hole

Trying to optimize image builds with Spring Boot **layered jars** (`extract --layers` +
per-layer COPY) produced a **non-runnable image** — the `spring-boot-loader` layer
extracted empty, so `JarLauncher` was `ClassNotFound` at runtime, even though the jar
itself was perfectly valid. Chased this through Dockerfile paths and extraction syntax
before falling back to plain `java -jar app.jar` on the fat jar (which always works,
because the loader is inside the jar).

**Takeaway:** layered jars are a *push-size* optimization; the big build-speed win came
from the **Maven cache**, which is independent. Don't reach for fragile optimizations
before the simple, reliable path is working — and validate that an optimized image
*actually runs*, not just that it builds.

## Ordering / dependency traps in Flux and Helm

Several self-inflicted layering bugs:

- **CRD dry-run deadlock:** bundling MetalLB's *config* (IPAddressPool/L2Advertisement,
  which needs the CRDs) with its *install* (the HelmRelease that creates the CRDs) in one
  atomic Kustomization → the config's dry-run failed → the whole thing (including the
  install) was blocked. Fix: separate config into its own layer with `dependsOn`.
- **Root Kustomization recursion:** the Flux root scanned a directory and applied raw
  sub-manifests directly instead of delegating to sub-Kustomizations, losing the
  `dependsOn` ordering. Fix: an explicit `kustomization.yaml` listing only the
  Kustomization definitions.
- **HelmRelease default 5m timeout** was too short for cold-start installs; had to bump to
  15m and add `remediation.retries`.
- **`targetNamespace` conflicts:** one Kustomization forcing everything into `default`
  clashed with resources that must live in a specific namespace (`metallb-system`).

## The chase for a single app pod at the end

The final stretch — getting one `demo-app` pod to run — became a long chain of "recreate
the missing manual piece, retry, hit the next missing piece." This was death by a thousand
paper cuts, and it was a symptom of the un-committed-state problem above, compounded by
fatigue. The right move (in hindsight) was to stop, commit the WIP, and do a single
clean "close all the gaps" pass with fresh eyes rather than limp it across by hand.

## A declarative fix introduced a new, subtler bug

Bringing `kind-cluster.yaml` and the containerd registry config under home-manager (to
close a "these files aren't in Git" gap) silently broke them: home-manager symlinks its
managed files into `/nix/store`, but kind's `extraMounts` only bind-mounts the directory
they live in — not `/nix/store` itself. From inside a fresh node's mount namespace, the
symlink target didn't exist, so containerd found no `hosts.toml` and `demo-app` failed to
pull its image on an otherwise-correct rebuild. Only the acid test caught this; reading the
diff would not have (the file "existed" and had the right content, right up until something
tried to actually resolve it from a different mount namespace).

**Takeaway:** converting a plain file to a symlink (which is what most declarative
config-management tooling does under the hood) is not a content-neutral change if anything
downstream of it cares about the file being a *real, self-contained* file — bind mounts,
container images, and anything else that copies or mounts by path rather than reading
through the symlink first. Worth checking for this class of tool specifically whenever
"make it declarative" is the fix being applied.

## A fail-open fallback turned two small bugs into an unbounded-cost one

The CI trigger `CronJob` had two independent, individually-minor bugs: no git credentials
for the (private) app repo, and a Role missing the `patch` verb `kubectl apply` needs on an
existing object. Neither alone would have been quiet — both would normally just show up as
a visible error. But the script used `|| true` / `2>/dev/null` on the "read current state"
steps (intended to handle the *first-ever run*, when there's no prior state yet), and that
same fallback also swallowed the *auth* failure — turning "I couldn't tell what the latest
commit is" into "the latest commit is empty," which then never matched the stored state, so
the script concluded "new commit" and triggered a build. Every single 2-minute cycle. For
about 35 minutes, unnoticed, before it was caught by watching pipeline counts over time
rather than by reading the CronJob's YAML (which looked entirely reasonable).

**Takeaway:** a fallback meant for one specific, expected failure mode ("no state yet") can
silently absorb a completely different, unexpected one ("I have no idea what's going on")
if both produce the same empty/falsy value. In a script that *takes action* on a changed
value, make the "I couldn't read this" case fail loudly and do nothing, rather than letting
it fall through to the same code path as "yes, something legitimately changed."

## Two different DNS mechanisms looked like one, until they didn't

Both Tekton pods and the deployed app resolve `registry-local` successfully, which made it
easy to assume it was "just a Kubernetes Service, resolvable everywhere." It's actually
two unrelated things that happen to overlap: pods resolve it via CoreDNS + the Service
object (namespace-scoped); nodes pulling images resolve it via nerdctl's own bridge-network
container-name DNS (Kubernetes-unaware entirely). When `image-reflector-controller` (in a
different namespace) couldn't resolve it, the tempting fix — change the image string to the
fully-qualified Kubernetes name — would have "fixed" the controller while silently breaking
every node-level image pull, because nerdctl's DNS has never heard of Kubernetes Service
FQDNs. Caught by reasoning through *which specific consumer* was actually failing before
touching anything, rather than generalizing from "it's a DNS problem, use the FQDN."

## Both containers ran as root for the entire build, unnoticed

Neither `demo-app` nor `postgres` ever had a `securityContext` until a dedicated hardening
pass, well after both had been rebuilt, restarted, and rebuilt-from-scratch (the acid test)
multiple times. Running as root doesn't announce itself anywhere routine — `kubectl get
pods` looks identical, the app works fine, probes pass. It only surfaced from a deliberate
`kubectl exec ... -- id`. Retrofitting non-root onto Postgres specifically cost real
complexity: its data directory had already been written to as root, so forcing non-root
needed a one-time `initContainer` to `chown` the existing volume, whereas doing it from the
very first deploy would have been free (either the volume starts empty and owned correctly,
or the official image's own entrypoint drops privileges itself when it's allowed to start
as root and self-manage the transition).

## `envFrom` doesn't mean every tool that needs a credential can read it

The Postgres backup `CronJob` used `envFrom: secretRef: postgres-secret`, which sets
environment variables named exactly `POSTGRES_USER`/`POSTGRES_PASSWORD`/`POSTGRES_DB` — but
`pg_dump` (via libpq) specifically reads `PGPASSWORD`, not `POSTGRES_PASSWORD`. The job ran,
connected, and failed cleanly with an authentication error rather than silently doing
nothing — but it's a reminder that "the secret is mounted/exposed" and "the specific tool
in this container knows to look for it under this exact name" are different claims, and the
only way to know for certain is running the actual command, not reading the Deployment spec.

## A ServiceMonitor silently matched nothing, and nothing said why

Wiring Prometheus to `demo-app` looked complete — matching labels on the `ServiceMonitor`,
a correctly-routing Service, a named port lining up with the scrape endpoint — and produced
no error anywhere: not in the `ServiceMonitor`, not in the Prometheus Operator's logs, not
in the HelmRelease. The target just never appeared as scraped. Root cause:
`ServiceMonitor.spec.selector` matches a Service's own `metadata.labels`, not its
`spec.selector` (which only governs pod routing) — a distinction neither object's YAML
states explicitly, and one that's easy to get right by accident (many example manifests
put the same label in both places without explaining why both matter). Found only by
reading Prometheus's live generated scrape config for the specific job and noticing the
target was being discovered and then dropped by a relabel rule, not by rereading the YAML.

## Almost gave up on a real test rather than reading the actual dependency

A MockMvc-based controller test failed with two "package does not exist" errors in an
unfamiliar Spring Boot version. The fast, plausible-sounding conclusion — "this project's
renamed test starter must not include full web-slice testing support" — was wrong, and
acting on it (rewriting the test as a lesser plain-unit-test substitute) would have shipped
a weaker test permanently for a problem that was actually two specific, findable class
relocations, resolvable in a few minutes by inspecting the real jars from Maven Central.
**The tell, in hindsight:** the plausible conclusion was never actually checked against
evidence — it explained the symptom well enough to feel true.

## A Tekton workspace flaked once — and the "obvious" fix revealed the diagnosis was wrong

A `PipelineRun` failed at the image-push step because the jar the previous step had just
built "didn't exist," despite both steps sharing one declared workspace. The first
explanation reached for — `local-path-provisioner`'s node-local PVs, "shared" only if the
scheduler happens to co-locate every Task — was never actually verified (the failing run's
pods were gone by the time it was investigated) and turned out to be **wrong**: Tekton
already ships a built-in Affinity Assistant (on by default) that *guarantees* this exact
co-scheduling via a dedicated assistant pod every Task gets a required affinity to. Building
the "fix" is what exposed this — it failed immediately on contact with a mechanism that
shouldn't have allowed the original symptom in the first place. The real cause is still
unknown; the confident-sounding theory was retracted rather than left on record. See the
corrected twelfth lesson in `README.md`.

## Small but recurring papercuts

- `zsh` treating pasted `# comments` as commands (`command not found: #`).
- Pasting placeholder strings literally (`http://<EXTERNAL-IP>` → zsh redirect error).
- `tkn pipelinerun logs -f` stopping at task boundaries, looking like a hang.
- Fast-churning CrashLoopBackOff pods making logs impossible to catch (fix: scale to 0,
  run the image as a standalone `--restart=Never` pod to read a stable log).
