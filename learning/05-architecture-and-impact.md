# Architecture and impact

How the pieces fit together, why each layer exists, and what it is actually worth.

## The layered architecture

```
┌─────────────────────────────────────────────────────────────────┐
│  Windows host -> WSL2 -> Ultramarine Linux                        │
│                                                                   │
│  Declarative base (home-manager flake, install.sh)                │
│    shell, git, gh, direnv, tooling, buildkit user service          │
│                                                                   │
│  Rootless container runtime                                       │
│    containerd + nerdctl + buildkit (systemd --user, no root)       │
│                                                                   │
│  Registries (standalone, persist across cluster recreation)       │
│    pull-through caches (docker.io/quay/ghcr/k8s) + local push      │
│                                                                   │
│  kind cluster (3 nodes)                                            │
│                                                                   │
│    Flux (GitOps CD), reconciles everything from flux-infra         │
│      - infrastructure: MetalLB, Envoy Gateway (HelmReleases)       │
│      - metallb-config (own layer, dependsOn infrastructure)        │
│      - apps: demo-app + Postgres, Gateway/HTTPRoute                │
│      - tekton + tekton-pipeline (CI, Flux-managed)                 │
│      - observability + observability-config (kube-prometheus-      │
│        stack + demo-app ServiceMonitor, own layer, dependsOn)      │
│                                                                   │
│    Tekton (CI): clone, then maven (cached), then kaniko, then      │
│    push to the local registry. Triggered by a CronJob polling      │
│    the source repo.                                                │
│                                                                   │
│    Flux image automation: watches the registry for a new tag,      │
│    then auto-commits the Deployment update, and the apps           │
│    Kustomization redeploys it automatically.                       │
│                                                                   │
│    Workload: Spring Boot 4 + JPA, talking to Postgres (PVC)        │
└─────────────────────────────────────────────────────────────────┘

Dev loop: VS Code (Remote-WSL) with a direnv devShell providing JDK, Maven, and Gradle.
```

## Why each layer exists

| Layer | Purpose | Why it matters |
|---|---|---|
| home-manager flake | Declarative user environment | Reproducible base. `install.sh` rebuilds a whole machine, so nothing important is a step someone has to remember to do by hand. |
| Rootless containerd and buildkit | Run and build containers without Docker or root | Better security, since there is no root daemon, and it forces a real understanding of how the container runtime actually works. |
| Registries (cache and local) | Fast pulls, and a place to push built images | The cache survives a cluster recreation, so a slow pull from the real internet only ever happens once. The local registry is a CI target with no dependency on any cloud service. |
| kind (multi-node) | A production-shaped local Kubernetes cluster | Vanilla upstream Kubernetes with real multi-node scheduling, so the behavior is genuine, while the cluster itself stays disposable. |
| Flux (GitOps CD) | Continuously reconcile the cluster to match Git | Git is the source of truth. The cluster heals itself back to match Git on its own, and disaster recovery is just `flux bootstrap` again. |
| MetalLB and Envoy Gateway | LoadBalancer IPs and Gateway API routing | The same networking shapes a real production cluster uses, even in places where WSL limits how reachable they actually are. |
| PVCs | Persistent storage | Data outlives the pod that wrote it. This is the core idea behind decoupling storage from a workload's own lifecycle. |
| Tekton (CI, running inside the cluster) | Build images from source, inside the cluster itself | CI becomes just more manifests. Because Flux manages Tekton too, the CI system is itself reproducible from Git. |
| kube-prometheus-stack | Metrics, dashboards, and alerting | The gap between "it's running" and "I can actually see what it's doing" is exactly what operational maturity is made of. |
| Spring Boot and Postgres | The actual workload | The thing all of the infrastructure above exists to build and deploy in the first place. |

## Roles, kept clearly separate

These are different layers with different jobs, not competing tools, which is a common
point of confusion:

Flux handles continuous delivery and reconciliation. Its whole job is "keep the cluster
matching Git." It does not build anything itself.

Tekton handles continuous integration, meaning pipelines that turn source code into a
built artifact. It does not watch Git for changes on its own, and it does not reconcile
anything.

An event layer, something like Tekton Triggers or Argo Events, is the glue between the
two: "when X happens, do Y." It is the trigger, not the builder and not the reconciler.
This project uses a CronJob as a simple stand-in for that layer, explained below.

Crossplane, which is not used in this project, provisions external cloud infrastructure
as Kubernetes resources. It is an alternative to a tool like Terraform, not an
alternative to Flux. In a real setup the two would work together, with Crossplane's
resources still delivered through Flux the same way any other manifest is.

The full CI/CD loop described below is built and verified end to end in this project, not
just a diagram of an intended design. A CronJob stands in for the event layer here,
because a real webhook needs GitHub to reach back into this machine, and this WSL setup
has no way to accept that inbound connection:

```
git push -> a CronJob polls the repo (pull-based) -> Tekton builds an image with a
unique tag -> the image is pushed to the registry -> Flux image automation notices the
new tag -> Flux commits the Deployment update back to Git -> Flux deploys it
```

## What this is actually worth

**Reproducibility.** The end goal was: take a fresh machine, run `install.sh`, run
`kind create`, run `flux bootstrap`, and have the entire platform (infrastructure, app,
and CI) rebuild itself from Git alone. This is no longer just a goal. It was actually run
for real: the cluster was deleted, recreated, bootstrapped again, and had its SOPS key
reprovisioned, and both `demo-app` and `postgres` came back up from Git with no other
manual steps. That turns "my personal setup" into something that is actually reproducible
rather than tribal knowledge living only in one person's head, with one honest exception:
the SOPS decryption key itself can never be stored in Git, since that would defeat the
whole point of encrypting secrets, so a human always has to bring that one key back by
hand. See `06-open-gaps-and-next-steps.md` for what is still missing beyond
reproducibility itself.

**Production-shaped learning.** Every concept used here transfers directly to a real
cluster: GitOps, Helm, the Gateway API, LoadBalancer services, persistent volumes,
running CI inside the cluster, image caching, dependency ordering between layers, and
secret management. The friction that shows up on WSL specifically is environmental, not
a gap in the underlying concepts. The same knowledge applies unchanged on a real Linux
box or a cloud cluster, where the networking limitations simply do not exist.

**Security posture.** Everything here runs rootless. Secrets are SOPS-encrypted in Git,
so there is no plaintext credential anywhere in `flux-infra`. Both `demo-app` and
`postgres` run as non-root, with `allowPrivilegeEscalation` set to false and every Linux
capability dropped. A default-deny `NetworkPolicy` sits in the `default` namespace with
explicit allow rules layered on top of it, and that policy was actually tested against
the cluster's real networking plugin rather than just assumed to work. Both workloads
also have `automountServiceAccountToken` set to false, since neither one needs to talk to
the Kubernetes API. What is still open: TLS on the Gateway, and the extra API-gateway
features Envoy Gateway can provide through its own `SecurityPolicy` and
`BackendTrafficPolicy` custom resources, like rate limiting and authentication, which
are not configured yet. See `06-open-gaps-and-next-steps.md`.

**Reliability.** `demo-app` runs two replicas behind a PodDisruptionBudget. `postgres`
stays a single replica since this is a homelab setup with no real replication in place,
but it still has its own PodDisruptionBudget with `maxUnavailable` set to zero, so it
cannot be evicted by accident, plus a daily `pg_dump` backup written to a separate
volume. What is still open: the underlying storage itself is not redundant, since it is
backed by `local-path` on a single node, and fixing that properly would need a different
storage layer entirely, like Longhorn or Rook and Ceph, which is out of scope for this
pass. One thing worth calling out honestly here: an intermittent Tekton build failure was
initially, and wrongly, blamed on this same kind of node-locality problem. Tekton's own
built-in Affinity Assistant already prevents that specific scenario structurally, so that
diagnosis was retracted once it was checked properly. The real cause is explained in the
twelfth lesson in `README.md`.

**Observability.** `kube-prometheus-stack`, which bundles Prometheus, Grafana,
Alertmanager, node-exporter, and kube-state-metrics, is installed through Flux in its own
namespace and its own Kustomization layer. `demo-app` exposes real JVM and HTTP metrics
through Micrometer, scraped every 15 seconds. Getting there caught a genuinely subtle
bug with no error message anywhere: a `ServiceMonitor` matches a Service's own
`metadata.labels`, not its `spec.selector`, which look similar but serve completely
different purposes. See the tenth lesson in `README.md` for the full story.

**Operational intuition.** The real payoff of all this debugging is the set of things now
understood from the inside rather than half-remembered from a tutorial: buildkit network
namespaces, cgroup delegation, inotify limits, the order custom resource types have to be
installed in, the difference between kubelet-level and pod-level DNS, PVC constraints,
socket versus TCP authentication, and cold-start timing. That kind of understanding only
comes from making each piece work while it actively resists you, and it is the difference
between being able to run a command and actually knowing why that command is the right
one.

## The honest boundary

This platform is a strong fit for learning and local development. Its limits are
environmental, caused by WSL, not architectural flaws in the design itself: there is no
inbound networking, so webhooks would need a tunnel; LoadBalancer IPs are not reachable
directly from the host; and clusters do not survive a restart on their own. For anything
that needs to stay always on or be reachable from outside the machine, the exact same
architecture belongs on a real Linux VM or a cloud cluster, where every pattern used here
works unchanged and the friction described in this project simply disappears.
