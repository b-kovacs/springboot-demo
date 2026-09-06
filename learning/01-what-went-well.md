# What Went Well

Decisions and habits that paid off, worth repeating on the next project.

## Declarative from the start

The whole user environment (shell, git, gh, tools, the buildkit service) lives in a
home-manager flake, a Nix configuration file that describes exactly what should be
installed and how it should be set up. Adding a new tool meant editing that file and
running one command, not running a one-off install command that nobody would remember
later. The habit this set early on: if it matters, it goes in a file, not just onto the
machine.

Why it worked: there's one source of truth, it's version-controlled, and a single
`install.sh` script can rebuild the whole machine from nothing.

## A separate dev environment per project

Instead of one global JDK, Maven, and Gradle installation shared across everything, each
project got its own environment definition that loads automatically when you enter that
project's folder (through a tool called direnv) and unloads when you leave it. Different
projects can use different versions of the same tool with no conflict at all.

Why it worked: the JDK, Maven, and Gradle for a given project only exist while you're
actually inside that project. That's a clean way to keep per-project toolchains from
polluting each other.

## Rootless containers, run as declarative background services

containerd and buildkit run as ordinary user-level background services, not as a root
daemon. Getting this working forced a real understanding of how the container runtime
works under the hood, since none of the usual root-owned shortcuts were available. No
Docker, nothing running as root: a security improvement and a forcing function for
learning the internals at the same time.

## Picking the boring, well-documented tool at every fork in the road

- Envoy Gateway over more exotic routing options, because it had the best documentation
  and existing guides for `kind`.
- Flux for GitOps, because it's mature and works well in a setup like this one, where the
  cluster only ever pulls from Git rather than something pushing to it.
- Tekton for CI, because it's built entirely out of Kubernetes objects, so CI became just
  more manifests to manage the same way as everything else.
- Kaniko for building container images, because it's designed to build images from inside
  a cluster without needing a Docker daemon, which matches the rootless setup exactly.

Why it worked: at every one of these decisions, the mainstream choice had documentation
and specific guidance for `kind` and WSL that the more niche alternatives simply didn't.

## GitOps proved itself the hard way

The cluster died repeatedly, mostly from WSL restarts. Every time, recovery was the same:
recreate an empty cluster, run one bootstrap command, and watch Flux rebuild the entire
stack from Git on its own. Watching the load balancer, the gateway, the app, and the
database all come back from that one command was the moment GitOps stopped being a
concept and became something proven by repetition.

To reproduce this live:

```bash
kind delete cluster
kind create cluster --config ~/kind-cluster.yaml
export GITHUB_TOKEN=$(gh auth token)
flux bootstrap github --owner=<you> --repository=flux-infra --path=clusters/kind --branch=main --personal
# then, since the SOPS key is deliberately not in Git:
kubectl create secret generic sops-age -n flux-system --from-file=age.agekey=~/.config/sops/age/keys.txt
flux get kustomizations   # watch them all go Ready
kubectl get pods -A       # watch demo-app and postgres come up
```

Real output, all nine Kustomizations Ready after a fresh bootstrap:

```
NAME                	REVISION          	SUSPENDED	READY	MESSAGE
apps                	main@sha1:1a4d00a8	False    	True 	Applied revision: main@sha1:1a4d00a8
flux-system         	main@sha1:1a4d00a8	False    	True 	Applied revision: main@sha1:1a4d00a8
image-automation    	main@sha1:1a4d00a8	False    	True 	Applied revision: main@sha1:1a4d00a8
infrastructure      	main@sha1:1a4d00a8	False    	True 	Applied revision: main@sha1:1a4d00a8
metallb-config      	main@sha1:1a4d00a8	False    	True 	Applied revision: main@sha1:1a4d00a8
observability       	main@sha1:1a4d00a8	False    	True 	Applied revision: main@sha1:1a4d00a8
observability-config	main@sha1:1a4d00a8	False    	True 	Applied revision: main@sha1:1a4d00a8
tekton              	main@sha1:1a4d00a8	False    	True 	Applied revision: main@sha1:1a4d00a8
tekton-pipeline     	main@sha1:1a4d00a8	False    	True 	Applied revision: main@sha1:1a4d00a8
```

## Caching the Maven build was the single best speed win

Pointing Maven's local dependency cache at a location that persists between builds, so
dependencies only get downloaded once instead of on every single build, cut build time
from around four minutes to about nine seconds on a warm cache. It's the same idea as
giving the database persistent storage, just applied to CI instead: don't throw away
something expensive to rebuild if you don't have to.

The actual Tekton step:

```bash
mvn clean package -B -Dmaven.repo.local=$(workspaces.source.path)/.m2
```

To see the difference live, compare a fresh `PipelineRun` right after wiping the
`tekton-workspace` PVC (cold, has to download every dependency) against a normal run
(warm cache):

```bash
kubectl get taskrun -n default -l tekton.dev/pipeline=build-app \
  -o jsonpath='{range .items[*]}{.metadata.name}{" "}{.status.startTime}{" -> "}{.status.completionTime}{"\n"}{end}'
```

Real output from this cluster, warm-cache builds, each one 16 to 18 seconds:

```
build-app-run-xntzk-maven-build 2026-09-06T15:27:17Z -> 2026-09-06T15:27:34Z
build-app-run-zmmh7-maven-build 2026-09-06T14:23:48Z -> 2026-09-06T14:24:06Z
build-app-run-zn2rz-maven-build 2026-09-06T13:44:06Z -> 2026-09-06T13:44:15Z
build-app-run-zwvtk-maven-build 2026-09-06T14:36:57Z -> 2026-09-06T14:37:13Z
build-app-run-zzdll-maven-build 2026-09-06T16:00:07Z -> 2026-09-06T16:00:23Z
```

The first-ever build on an empty cache, before anything was downloaded, took around four
minutes instead.

## Checking events before assuming something is broken

A pod that looks stuck is very often just slow, not broken. Checking a pod's event log,
or just looking at how long an image pull has been running, repeatedly turned out to show
a normal, if slow, cold image pull rather than an actual failure. On a fresh cluster,
these pulls can take minutes. Learning to tell "slow" apart from "failed" saved a lot of
time that would otherwise have gone into chasing problems that didn't exist.

```bash
kubectl describe pod -n <ns> <pod> | grep -A10 Events
```

Sample output, a genuinely slow but healthy pull, not a failure:

```
Events:
  Type    Reason     Age   From               Message
  ----    ------     ----  ----               -------
  Normal  Scheduled  4m2s  default-scheduler  Successfully assigned ...
  Normal  Pulling    4m1s  kubelet            Pulling image "registry.k8s.io/..."
```

No `Warning` line, and the pull is still listed as `Pulling` several minutes in. That's
slow, not stuck. A real failure shows a `Warning` with `Failed` or `BackOff`.

## Checking the starting state before trusting a test's result

Before running any test meant to prove something (like deleting a pod to confirm its data
survives), first confirming the thing being tested for was actually there to begin with.
This caught more than one false alarm where a write had simply never happened, which would
have otherwise looked exactly like "persistence is broken."

## Debugging the fundamentals paid off far beyond the immediate fix

Every one of the low-level problems worked through by hand (how buildkit's namespaces
work, how cgroup delegation works, inotify limits, ordering issues with custom resources,
how DNS resolution differs between different network paths, storage constraints) built
real, lasting understanding of how this stack actually works underneath the tools. The
debugging itself was the learning, not a distraction from it.

## The full rebuild test actually ran, and caught a real bug before it could matter

Deleting the cluster, recreating it, bootstrapping Flux, reloading the encryption key, and
watching everything come back up, app and database included, wasn't just something planned
or claimed. It was actually run. That's the strongest evidence that the goal of a fully
reproducible system was actually met, not just aimed at. It even caught a real bug (a
change that broke image pulls on a freshly created node) before that bug could cause a
problem on a day when the rebuild actually mattered for real. That's the entire point of
running a destructive test on purpose instead of just hoping everything would work.

## Verifying every fix against the running system, not just checking it applied

The habit that made the secrets setup, the credential rotation, and the health check
additions all work cleanly on the first real attempt: after making a change, actually
rerun the thing it's supposed to affect. Rerun the pipeline after rotating a credential.
Watch how many times a pod restarts after adding a health check. Query the registry
directly instead of trusting that a build step exiting successfully means the image
actually exists where it should. Never assume a fix worked just because the configuration
looks right or a command didn't return an error.

## The automatic deployment loop was proven with a real change, not a manual test run

After setting up unique image tags, the job that watches for new commits, and Flux's
automatic image updates, the real test was adding an actual new feature to the app,
pushing it, and then doing nothing: watching the scheduled job notice the change, watching
the pipeline build and push a new image, watching Flux notice that image and commit a
deployment update on its own, and watching the new version actually come up and serve
traffic. That end-to-end test caught a real bug in the scheduled job (described in
`02-what-went-badly.md`) that no amount of reading the configuration would have caught,
since the bug only showed up in how the job actually behaved when run repeatedly, not in
how it looked on paper.

## Going to the real published files instead of guessing at an unfamiliar API

A test using Spring's MockMvc failed to compile because two classes weren't where
expected, in an unfamiliar, recently updated version of Spring Boot. Rather than settle
for a weaker test as the final answer, the actual published files for those dependencies
were downloaded from Maven Central and inspected directly. Looking inside the real file
answers "where did this class actually go" for certain, in a few minutes, no matter how
unfamiliar the dependency is. That habit turned what could have been "I'll just settle for
less" into finding the real answer (two classes had moved, nothing else had) and getting
the originally intended test working properly.

```bash
curl -sL "https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-webmvc-test/4.1.1/spring-boot-webmvc-test-4.1.1.jar" -o x.jar
unzip -l x.jar | grep WebMvcTest.class
```

Real output:

```
     2007  02-01-1980 00:00   org/springframework/boot/webmvc/test/autoconfigure/WebMvcTest.class
```

That's the actual, current package, found in under a minute instead of guessing.

## Testing a security control before trusting it

Before writing a real network policy, the specific plugin this cluster uses for
networking was tested directly, in a disposable namespace: deploy a rule that blocks
everything, then confirm a test client actually gets blocked. This is the same "check, don't
assume" habit that paid off throughout this project, applied to a case where being wrong
would have mattered more than usual. A network policy that's silently doing nothing looks
completely identical, from a normal `kubectl get`, to one that's actually working.

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
kubectl logs np-client -n netpol-test
kubectl delete namespace netpol-test
```

Real output from this exact test:

```
000
BLOCKED
```

A `200` here would have meant this cluster's networking plugin ignores `NetworkPolicy`
entirely, silently. `BLOCKED` confirms it's actually enforced.
