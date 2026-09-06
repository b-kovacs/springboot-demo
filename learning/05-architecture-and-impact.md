# Architecture & Impact

How the pieces fit together, why each layer exists, and what it's actually worth.

## The layered architecture

```
┌─────────────────────────────────────────────────────────────────┐
│  Windows host  ──  WSL2  ──  Ultramarine Linux                    │
│                                                                   │
│  ┌─ Declarative base (home-manager flake, install.sh) ─────────┐ │
│  │   shell, git, gh, direnv, tooling, buildkit user service     │ │
│  └──────────────────────────────────────────────────────────────┘ │
│                                                                   │
│  ┌─ Rootless container runtime ─────────────────────────────────┐ │
│  │   containerd + nerdctl + buildkit  (systemd --user, no root)  │ │
│  └──────────────────────────────────────────────────────────────┘ │
│                                                                   │
│  ┌─ Registries (standalone, persist across cluster recreation) ─┐ │
│  │   pull-through caches (docker.io/quay/ghcr/k8s) + local push  │ │
│  └──────────────────────────────────────────────────────────────┘ │
│                                                                   │
│  ┌─ kind cluster (3 nodes) ─────────────────────────────────────┐ │
│  │                                                               │ │
│  │   Flux (GitOps CD) ── reconciles everything from flux-infra   │ │
│  │     ├── infrastructure: MetalLB, Envoy Gateway (HelmReleases)  │ │
│  │     ├── metallb-config (own layer, dependsOn infrastructure)  │ │
│  │     ├── apps: demo-app + Postgres, Gateway/HTTPRoute          │ │
│  │     ├── tekton + tekton-pipeline (CI, Flux-managed)           │ │
│  │     └── observability + observability-config (kube-prometheus- │ │
│  │         stack + demo-app ServiceMonitor, own layer/dependsOn)  │ │
│  │                                                               │ │
│  │   Tekton (CI) ── clone → maven(cached) → kaniko → local reg   │ │
│  │     ▲ triggered by a CronJob polling the source repo           │ │
│  │   Flux image automation ── registry → new tag → auto-commit   │ │
│  │     └─► apps Kustomization redeploys automatically             │ │
│  │                                                               │ │
│  │   Workload: Spring Boot 4 + JPA  ──►  Postgres (PVC)          │ │
│  └──────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘

Dev loop:  VS Code (Remote-WSL) + direnv devShell (JDK/Maven/Gradle)
```

## Why each layer exists

| Layer | Purpose | Why it matters |
|---|---|---|
| **home-manager flake** | Declarative user env | Reproducible base; `install.sh` rebuilds a machine. Nothing important is a forgotten manual step. |
| **Rootless containerd/buildkit** | Run/build containers without Docker or root | Security (no root daemon) and a genuine understanding of the runtime. |
| **Registries (cache + local)** | Fast pulls; a place to push built images | Cache survives cluster recreation → cold pulls happen once. Local registry = CI target with no cloud dependency. |
| **kind (multi-node)** | Production-shaped local Kubernetes | Vanilla upstream, multi-node scheduling — real k8s behavior, disposable. |
| **Flux (GitOps CD)** | Reconcile cluster ⇐ Git, continuously | Git is the source of truth; cluster self-heals to match; disaster recovery = `flux bootstrap`. |
| **MetalLB / Envoy Gateway** | LoadBalancer IPs / Gateway API routing | The production networking shapes (even where WSL limits reachability). |
| **PVCs** | Persistent storage | Data outlives pods — the storage/lifecycle-decoupling concept. |
| **Tekton (CI, in-cluster)** | Build images from source, in the cluster | CI becomes "just more manifests"; Flux-managed → the CI system itself is reproducible. |
| **kube-prometheus-stack** | Metrics, dashboards, alerting | The gap between "it's running" and "we can see what it's actually doing" — the difference operational excellence is made of. |
| **Spring Boot + Postgres** | The actual workload | The thing all the infrastructure exists to run and deploy. |

## Roles, clearly separated (a key mental model)

These are **different layers, not competitors** — a common point of confusion:

- **Flux = CD / reconciliation** — "keep the cluster matching Git." Not a builder.
- **Tekton = CI / pipelines** — "turn source into an artifact." Doesn't watch Git or reconcile.
- **Event layer (Tekton Triggers / Argo Events) = the glue** — "when X happens, do Y." The
  trigger, not the builder or reconciler.
- **Crossplane** (not used here) — provisions *external cloud infra* as k8s resources; an
  alternative to Terraform, **not** to Flux. Would compose *with* Flux, delivered via GitOps.

The full production CI/CD loop — **this is now built and verified end-to-end**, not just
diagrammed (a `CronJob` stands in for the event layer, since a real webhook needs inbound
reachability this WSL setup doesn't have):

```
git push → [CronJob polls, pull-based] → Tekton builds unique-tagged image → registry
                                                                                │
                              Flux image automation detects the new tag ◄──────┘
                                                                                │
                             Flux auto-commits the Deployment update to Git ────┘ → Flux deploys
```

## Impact — what this is worth

**Reproducibility.** The end-state goal: a fresh machine → `install.sh` → `kind create` →
`flux bootstrap` → the entire platform (infra, app, CI) rebuilds from Git. **This is no
longer aspirational — it was run for real** (`kind delete cluster` → recreate → bootstrap →
re-provision the SOPS key), and both `demo-app` and `postgres` came up from Git alone. That
turns "my setup" from tribal knowledge into an artifact, with one honest asterisk: the SOPS
decryption key itself can never live in Git, so a human always has to bring that one key
back. See `06-open-gaps-and-next-steps.md` for what's left beyond reproducibility
(observability, CI triggers, security hardening, reliability).

**Production-shaped learning.** Every concept here transfers directly to real clusters:
GitOps, Helm, Gateway API, LoadBalancer, PVCs, in-cluster CI, image caching, dependency
ordering, secret management. The WSL friction is environmental, not conceptual — the
knowledge is portable to cloud/bare-metal where the networking "just works."

**Security posture.** Rootless throughout; secrets are now SOPS-encrypted in Git (no
plaintext credential in `flux-infra`); `demo-app` and `postgres` run as non-root with
`allowPrivilegeEscalation: false` and all capabilities dropped; default-deny `NetworkPolicy`
in `default` namespace with explicit allows (verified the CNI actually enforces this, not
assumed); `automountServiceAccountToken: false` on both workloads. Still open: TLS on the
Gateway, and the API-gateway features (rate limiting, auth) Envoy Gateway could provide via
its `SecurityPolicy`/`BackendTrafficPolicy` CRDs but doesn't yet (see
`06-open-gaps-and-next-steps.md`).

**Reliability.** `demo-app` runs 2 replicas behind a PodDisruptionBudget; `postgres` stays
single-replica (no replication set up — this is a homelab, not HA) but has its own PDB
(`maxUnavailable: 0`) protecting it from an accidental voluntary eviction, plus a daily
`pg_dump` backup to a separate PVC. Still open: storage itself isn't redundant
(`local-path`, single node) — genuinely hard to fix without a different storage layer
(Longhorn, Rook/Ceph), out of scope for this pass. (An intermittent Tekton build flake was
initially blamed on this same node-locality — Tekton's built-in Affinity Assistant actually
already prevents that specific scenario structurally, so that diagnosis was retracted; see
the twelfth lesson in `README.md`.)

**Observability.** `kube-prometheus-stack` (Prometheus, Grafana, Alertmanager,
node-exporter, kube-state-metrics) via Flux, in its own namespace/Kustomization layer;
`demo-app` exposes real JVM/HTTP metrics via Micrometer, scraped every 15s. Caught a subtle,
error-free bug getting there: a `ServiceMonitor` matches a Service's own `metadata.labels`,
not its `spec.selector` — see the tenth lesson in `README.md`.

**Operational intuition.** The real payoff of the debugging: buildkit namespaces, cgroup
delegation, inotify limits, CRD ordering, DNS scoping (kubelet vs pod), PVC constraints,
socket-vs-TCP auth, cold-start timing. This is understanding that only comes from making
each piece work when it fights you — and it's the difference between running commands and
knowing *why*.

## The honest boundary

This platform is ideal for **learning and local development**. Its limits are all
**environmental (WSL)**, not architectural: no inbound networking (webhooks need tunnels),
LoadBalancer IPs unreachable from the host, clusters ephemeral across restarts. For
anything requiring always-on availability or external reachability, the same architecture
belongs on a real Linux VM or cloud cluster — where every pattern here works unchanged and
the friction disappears.
