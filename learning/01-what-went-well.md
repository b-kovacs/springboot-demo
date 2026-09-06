# What Went Well

Decisions and patterns that paid off, and are worth repeating.

## Declarative-first, from the ground up

Putting the whole user environment in a **home-manager flake** (shell, git, gh, tools,
buildkit service) meant the base machine was reproducible from the start. Adding a tool
was an edit + `hms`, not a forgotten one-off `apt install`. This set the tone: *if it
matters, it goes in a file.*

**Why it worked:** one source of truth, version-controlled, and an `install.sh` that
rebuilds a fresh machine.

## Per-project dev environments with direnv + flake devShells

Instead of a global JDK/Maven/Gradle, each project got its own `flake.nix` devShell,
auto-loaded by `direnv` on `cd`. Different projects can pin different toolchain versions
with zero conflict.

**Why it worked:** the JDK/Maven/Gradle exist only inside the project; leaving the dir
unloads them. This is the Nix analogue of a clean, isolated per-project toolchain.

## Rootless containers as declarative user services

containerd + buildkit as `systemd --user` services (declared in home-manager, using the
rootless setuptool's `nsenter` wrapper for buildkit's namespace nesting). No Docker, no
root — which is both a security win and forced a clean understanding of how the runtime
actually works.

## Choosing the "boring, well-trodden" tool at each fork

- **ingress-nginx / Envoy Gateway** over exotic options — best docs, kind-specific manifests.
- **Flux** for GitOps — mature, first-party image automation, pull-based (works on WSL).
- **Tekton** for CI — Kubernetes-native, so CI became "just more manifests."
- **Kaniko** for image builds — designed to build in-cluster without a Docker daemon,
  which is exactly the rootless constraint.

**Why it worked:** at every fork, the mainstream choice had the documentation and the
kind/WSL-specific guidance that the niche options lacked.

## GitOps proved its worth under fire

When the cluster died (repeatedly, via WSL restarts), recovery became: recreate the empty
cluster → `flux bootstrap` → Flux rebuilds the entire stack from Git. Watching MetalLB,
Envoy, the app, and Postgres all come back from a single bootstrap was the payoff moment —
and the clearest demonstration of *why* GitOps matters.

## The Maven build cache (persistent-storage concept, reused)

The single most effective optimization: pointing Maven's local repo at a persistent
location so dependencies are downloaded **once**, not every build. Build time dropped from
~4 minutes to ~9 seconds on warm rebuilds. It's the same PVC/persistence concept already
learned with Postgres, applied to CI — a nice conceptual through-line.

## Reading tool events instead of guessing

The habit that repeatedly saved time: when a pod looked "stuck," checking
`kubectl describe pod ... | Events` or the image-pull duration revealed it was **slow, not
broken** (cold image pulls on a fresh cluster routinely took minutes). Distinguishing
"slow" from "failed" prevented a lot of wasted intervention.

## Verifying preconditions before concluding

The discipline of *confirming the starting state before the destructive test* — e.g.
proving a DB row existed before deleting the pod to test persistence — stopped several
false conclusions ("persistence failed!" when the write had simply never landed).

## Debugging the fundamentals paid compounding dividends

Every failure fixed by hand (buildkit namespaces, cgroup delegation, inotify limits, CRD
ordering, DNS scoping, PVC constraints) built real operational intuition. The debugging
*was* the learning — this stack is now understood at a level no tutorial provides.

## The acid test actually ran, and it worked (with one bug caught, not shipped)

`kind delete cluster` → recreate → `flux bootstrap` → re-provision the SOPS key → watch
everything come up, `demo-app` and `postgres` included. This wasn't a claim, it was run for
real, and it's the single strongest piece of evidence that the "immutable, reproducible
system" goal was actually met, not just aimed at. It even caught a real bug (a home-manager
change breaking kind's `extraMounts` via `/nix/store` symlinks) *before* it could bite on a
day the rebuild actually mattered — which is the entire point of running the test
deliberately instead of hoping.

## Verifying every fix live, not just checking that it applied

The pattern that made the SOPS setup, the credential rotation, and the probe additions all
land cleanly on the first real attempt: after each change, re-run the actual thing it
affects (re-run the Tekton pipeline after rotating `git-credentials`, watch pod restart
counts after adding probes, query the registry's own API instead of trusting a TaskRun's
exit code) rather than trusting that "the manifest looks right" or "the command succeeded"
means the fix worked.

## The image automation loop was proven with a real commit, not a manual PipelineRun

After wiring up unique tags, the CI trigger `CronJob`, and Flux image automation, the actual
test was: add a real endpoint to the app, `git push`, and watch — untouched — a `CronJob`
notice it, Tekton build and push a uniquely-tagged image, Flux detect the tag and commit a
Deployment update, and the new pod come up serving the new endpoint. That end-to-end proof
caught the CronJob's runaway-trigger bug (see `02-what-went-badly.md`) that a "does the
YAML look right" review would have missed entirely — the bug only existed in the gap
between "the manifest applies" and "the job's own logic behaves correctly at runtime," which
only running it for real, more than once, exposes.

## Reading the real jar instead of guessing at a relocated API

A MockMvc test failed to compile against unfamiliar package paths in a newer/relocated
Spring Boot. Rather than accept a lesser test (plain Mockito, no real HTTP dispatch) as the
final answer, the actual dependency POMs and jars were pulled from Maven Central and
inspected directly — `unzip -l` on the real artifact answers "where did this class go"
definitively, in minutes, for any dependency, however unfamiliar. That habit turned an
"I'll settle for less" moment into finding the real answer (two classes relocated, everything
else unchanged) and getting the originally-intended test working.

## Testing the security mechanism before trusting it

Before writing any `NetworkPolicy`, the actual CNI (`kindnet`) was tested empirically in a
throwaway namespace — deploy a deny-all policy, confirm a client genuinely gets blocked —
rather than assumed from general Kubernetes knowledge. This is the same "verify, don't
assume" habit that paid off all through this build, applied to a case where getting it
wrong would have been worse than usual: a NetworkPolicy that's silently a no-op looks
identical, in `kubectl get`, to one that's actually enforced.
