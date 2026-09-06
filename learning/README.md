# Homelab Platform: Build Learnings

A record of building a full, self-hosted Kubernetes platform on a rootless, declarative
base under WSL2. This covers what went right, what went wrong, and how each problem got
debugged.

## Purpose

Three goals from the start:

1. Make the whole environment reproducible. Everything should be defined declaratively and
   rebuildable from source, so nothing important lives only in someone's head or in a step
   nobody wrote down.
2. Learn Kubernetes the way it actually gets used in production, not a toy cluster. That
   means multiple nodes, real container builds, a gateway, load balancing, persistent
   storage, GitOps for deployment, and CI running inside the cluster itself.
3. Learn Spring Boot and Java properly, not by writing a throwaway CRUD stub. That means a
   real layered architecture (Controller, Service, Repository, and DTOs instead of an
   entity exposed directly over the API), a real three-tier testing strategy with actual
   mocks instead of no tests at all, and deliberate choices behind the CRUD and API design,
   not just whatever Spring's defaults happen to produce.

## What was built

- **A declarative OS layer.** Ultramarine Linux under WSL2, with the whole user
  environment defined in a Nix and home-manager flake, rebuildable from a single
  `install.sh` script.
- **A rootless container runtime.** containerd, nerdctl, and buildkit, all running as
  declarative user-level systemd services. No Docker daemon, nothing running as root.
- **A multi-node Kubernetes cluster**, using `kind` (vanilla, upstream Kubernetes running
  as containers), plus a pull-through cache for external images and a local registry to
  push built images to.
- **A real application.** A Spring Boot service with a database, built with VS Code over
  Remote-WSL and containerized with the local buildkit setup.
- **Networking through the Gateway API.** This is the newer, Kubernetes-native way to
  route traffic (the objects are called `GatewayClass`, `Gateway`, and `HTTPRoute`),
  running through Envoy Gateway, plus MetalLB to hand out real IP addresses to load
  balancer Services. Worth being precise here: "Gateway API" and "API Gateway" are
  different things that happen to overlap in this setup. An API Gateway usually means
  extra features like authentication and rate limiting. Envoy Gateway can do that too
  through its own extra resources, but none of that is configured yet.
- **Persistent storage**, tested by actually deleting pods and confirming the data
  survived.
- **GitOps deployment with Flux**, which watches a Git repository (`flux-infra`) and keeps
  the cluster matching it. Proven by deleting the cluster entirely and rebuilding it from
  that repo alone, with one manual step: reloading the encryption key used for secrets
  (explained in lesson four).
- **Secrets encrypted before they ever reach Git**, using SOPS and age. No plaintext
  secret sits in the `flux-infra` repository.
- **A fully automated CI pipeline**, built with Tekton and itself managed by Flux. It
  clones the app, builds and tests it with Maven, builds a container image with Kaniko,
  and pushes it with a unique tag. A scheduled job checks the app repo for new commits,
  since a real webhook can't reach this machine (no way for GitHub to send a request
  through WSL and NAT).
- **Automatic deployment of new builds.** Flux watches the registry, notices a new image,
  and commits the update back into the `flux-infra` repo itself, closing the loop. Tested
  by pushing a real code change and watching it reach a running pod with nobody touching
  `kubectl`.
- **Production hardening**, done in stages: health checks and resource limits, a
  properly automated CI/CD pipeline, security and reliability work (network policies,
  non-root containers, backups), and monitoring with Prometheus. The one thing still open
  is TLS and extra features on the gateway.
- **Real application code, not a placeholder.** The app's core feature started as a
  database entity exposed directly over the API, with no service layer, no separate
  request/response objects, and no tests. It was refactored into a proper layered design,
  and the test suite actually runs in CI now.

## The documents in this folder

- [`01-what-went-well.md`](01-what-went-well.md): decisions and habits that paid off.
- [`02-what-went-badly.md`](02-what-went-badly.md): friction, dead ends, and the
  environmental problems (mostly WSL and rootless containers) that cost the most time.
- [`03-debugging-playbook.md`](03-debugging-playbook.md): how to diagnose the recurring
  problem types, with the actual commands.
- [`04-solutions-reference.md`](04-solutions-reference.md): concrete fixes, copy-paste
  ready.
- [`05-architecture-and-impact.md`](05-architecture-and-impact.md): how the pieces fit
  together, why each one exists, and what it's actually worth.
- [`06-open-gaps-and-next-steps.md`](06-open-gaps-and-next-steps.md): what's still manual
  or uncommitted, and the plan to close it.
- [`07-spring-boot-lessons.md`](07-spring-boot-lessons.md): the same kind of lessons,
  focused specifically on the Java and Spring Boot side: testing strategy, mocks, CRUD
  and API design, and how the app gets built and packaged.

## Lesson one: if it isn't committed to Git, it won't survive a fresh cluster

Every painful detour late in the build traced back to something created by hand instead
of through Git: a ServiceAccount, a Secret, a Service, a change made directly on a node.
All of it vanished the moment the cluster got recreated. This is the entire reason GitOps
exists, and the reason secrets need to be encrypted before they go into Git rather than
left out of it entirely. It's a simple rule, but it took getting burned by it repeatedly
to actually internalize.

## Lesson two: a pipeline reporting success doesn't mean the cluster is running that build

The app going into a restart loop looked like a database or Dockerfile problem. It wasn't.
Two separate infrastructure issues were compounding each other.

First, the local registry returned a 404 for an image tag that several "successful"
Tekton runs had supposedly pushed, even though it had a persistent volume attached. My
first guess was that it had lost its storage. That guess was wrong, and I want to flag
that clearly because an earlier version of this document stated it as fact. The volume
was fine. The more likely cause was that the network address the pods used to reach the
registry didn't match the registry container's actual address at the time of the push.
The real lesson: don't trust the first theory that sounds plausible without checking it
against the evidence.

Second, a worker node still had an old, broken image cached under that same tag. Because
the pull policy was set to only pull if the image wasn't already present, the node just
kept using its stale copy, no matter how many times the pipeline rebuilt the image.

Fixing this meant checking the actual state instead of trusting reported status: querying
the registry's own API for the image manifest, and checking what image was actually
cached on the node, rather than just checking whether the build step exited successfully.
Once both were confirmed stale, deleting the cached image and switching the pull policy to
always pull closed the gap. It's the same category of problem as lesson one: a tag that
can be silently reused, combined with a cache that doesn't know it's stale, creates a gap
between Git and reality just like an uncommitted manual change does.

## Lesson three: the full rebuild test finds bugs in the fix, not just in the original setup

I ran the real test: delete the cluster, recreate it, bootstrap Flux, reload the
encryption key, and watch everything come back. It worked, but not on the first attempt,
and the failure was caused by a fix made earlier in the same session. Making a
configuration file declarative through Nix turned it into a symlink pointing into the Nix
store. `kind`'s node setup only mounts the specific folder it's told to, not the whole
store path the symlink pointed to, so inside a fresh node the symlink target simply didn't
exist. containerd found no configuration file, fell back to a default it shouldn't have
used, and the app failed to pull its image on an otherwise correctly rebuilt cluster.

The fix was to also mount the Nix store itself into every node.

The lesson: making something declarative is a real change, not just paperwork that closes
a checklist item. "It's committed to Git" and "it actually works on a from-scratch
rebuild" are different claims, and only a full rebuild test tells them apart. It's worth
rerunning that test after any change to the reproducibility setup, not just once at the
end of a project.

## Lesson four: encrypting secrets closes one gap, but the encryption key itself can't be in Git

Setting up SOPS with age was mechanical: generate a keypair, load the private key into the
cluster as a Secret, add a config file telling SOPS what to encrypt, and tell Flux which
of its reconciliation targets are allowed to decrypt. The one easy thing to miss: on a
freshly rebuilt cluster, everything comes up fine except the parts that depend on
encrypted secrets, which get stuck until the private key is manually reloaded. That's not
a bug. The key deliberately isn't in Git, since putting it there would defeat the entire
point of encrypting secrets with it. But it does mean "zero manual steps" comes with one
honest exception: there will always be exactly one secret that has to come from outside
Git, and it needs to be documented and backed up as carefully as anything else in the
stack.

A related note on rotating a credential that had been exposed: GitHub doesn't provide any
way, API or otherwise, to create or delete a personal access token outside its web
interface. The practical workaround was using the GitHub CLI's own device-code login flow
to get a token tied to its OAuth app instead of a manually created one. That kind of token
can be fully managed from the command line afterward, including revocation, which trades a
little precision in scope for never needing to visit the tokens page again.

## Lesson five: an app "running" and Kubernetes knowing it's healthy are different claims

Both the app and the database had been running successfully this whole time with no
liveness or readiness checks and no resource limits set. That's easy to miss, because
nothing about it looks wrong. A pod with no health checks just shows as running forever,
whether or not it's actually serving traffic, and a pod with no resource limits works
fine right up until something else on the node needs memory or CPU it doesn't have.
Kubernetes' ability to replace an unhealthy pod or protect a node from a runaway container
is something you have to opt into per container. It isn't automatic.

Two specific things worth remembering:

- Plain Spring Boot has no health endpoint by default. Adding the Actuator dependency and
  one configuration flag gives you separate liveness and readiness endpoints for free, but
  it has to be added on purpose.
- Adding a health check to something that's already running can cause one restart that
  looks alarming but usually isn't. The database restarted once right after its liveness
  check was added, because the check's startup delay raced against the database's own
  first-time initialization. Watch the restart count over a couple of minutes before
  concluding a check is misconfigured.

## Lesson six: two separate DNS systems exist in this cluster, and mixing them up nearly caused the wrong fix

While setting up automatic image updates, the component watching the image registry
(running as a pod in a different namespace than everything else) couldn't resolve the
registry's hostname, even though every other consumer of that same name resolved it fine.
The instinctive fix, changing the registry's address to a fully qualified name, would have
been wrong, and understanding why is the actual lesson.

Regular pod-to-Service traffic resolves names through Kubernetes' own internal DNS, which
only resolves a short, unqualified Service name for pods in that Service's own namespace.
A pod in a different namespace needs the longer, qualified name.

Pulling a container image onto a node works completely differently. The node itself
resolves the registry's name through the container runtime's own network, the same
mechanism Docker or nerdctl uses to resolve one container's name to another on a shared
network. That has nothing to do with Kubernetes' DNS at all.

Both paths happened to work up to that point only because the Kubernetes Service pointing
at the registry was manually set to the exact same address as the actual container.
Changing the registry's name to the fully qualified version would have fixed the one
broken consumer while breaking every node's ability to pull the image, since the
container runtime's own DNS has no idea what a Kubernetes Service's full name means. The
actual fix touched only the one thing that was broken: a small rewrite rule in Kubernetes'
DNS server that redirects just the one namespace's queries for that name to the qualified
version, leaving every other path untouched.

## Lesson seven: a script that fails silently and keeps going is worse than one that just fails

The job that checks for new commits and triggers a build had two separate bugs: no
credentials for the private repository, and a missing permission needed to save its own
state. Individually, either one would have just caused a visible failure. Together, they
were worse. The command that checks the latest commit failed silently and returned
nothing, a fallback meant for a completely different situation ("there's no saved state
yet") quietly accepted that empty result, an empty value never matched the stored one, so
the script concluded there was a new commit and triggered a build, every single cycle,
forever. The final step that was supposed to save the new state also failed, because of
the missing permission, so nothing ever settled. It ran unnoticed for about half an hour
before it was caught, and it was caught by watching how many pipeline runs were piling up,
not by reading the script and spotting the bug.

The general point: in any automation that takes an action, not just one that reports
something, it's better to fail loudly and do nothing than to silently fall back to a value
that makes the "go ahead and act" branch look correct. Fallbacks that swallow errors are
fine for genuinely optional reads. They're dangerous on the one read where a failure
should stop the whole run. The fix was explicit: check that the value actually came back
non-empty, and exit with an error immediately if it didn't, before any decision gets made.

## Lesson eight: nothing breaking doesn't mean it's secure

Neither the app nor the database had ever had a security context set. Both had been
running as the root user since the very first successful deploy, through every rebuild
and every full cluster rebuild after that, completely unnoticed, because running as root
doesn't look any different in a pod listing, and nothing in the testing done up to that
point happened to check who a process runs as. It took one specific command, checking the
running user inside the container directly, to notice it at all.

Fixing it wasn't free. The official Postgres image is normally trusted to start as root
and drop its own privileges. But because it had genuinely been running as root already,
its data directory was owned by root, so switching it to run as a non-root user directly
needed a one-time setup step (still running as root, just for that one step) to fix
ownership of the existing files before the main container could run unprivileged.

The general point: a security property that would have been free if it were set from day
one can require a real, careful migration once real data already exists under the old
assumption. It's worth checking for this kind of thing before state builds up under an
insecure default, not after.

## Lesson nine: test a security control before trusting it

Before writing a real network policy (a Kubernetes firewall rule between pods), I first
checked, in a throwaway namespace, whether the cluster's networking plugin actually
enforces that kind of rule at all, rather than assuming it does because Kubernetes
supports the feature in general. This mattered because some networking plugins have, at
points in their history, silently accepted a network policy without enforcing it, which
is arguably worse than having no policy, since it looks like protection that isn't
actually there. This specific cluster's plugin does enforce it, confirmed by the test
actually blocking traffic. But the habit matters more than that one result: a security
control that quietly does nothing is worse than not having it, because it actively
suggests a protection that isn't real. Test the mechanism before relying on it, every
time, regardless of what worked on the last cluster or what the documentation claims.

## Lesson ten: a monitoring rule matches a Service's labels, not its selector, and that's easy to get wrong

Setting up Prometheus to scrape the app looked complete: the rule telling it what to watch
matched the right label, the Service routed to the right pods, the port name lined up.
Nothing produced an error. Prometheus just never scraped it. The target didn't even show
up as "down," it wasn't in the list at all.

The actual mechanism: the rule that tells Prometheus what to scrape matches a Service by
its own labels, which get copied onto the Service's underlying network endpoints. That is
completely separate from the selector that controls which pods the Service actually
routes traffic to. The app's Service had a working selector, so real traffic routed fine,
but it had no labels of its own, so the monitoring rule never matched the Service itself,
even though it matched every pod perfectly.

Where this actually showed up: not as an error, but buried in Prometheus's own list of
discovered-but-filtered-out targets, dropped by an internal rule checking for a label that
simply wasn't there. Found by reading Prometheus's own generated configuration for that
specific job line by line, not by staring at the YAML files, which looked completely
correct on their own. The bug lived in an assumption connecting two files, not in either
file individually.

## Lesson eleven: don't guess at an unfamiliar framework's API, go check the real package

Writing a test using Spring's `MockMvc` failed to compile. Two specific things "didn't
exist": the annotation that boots a web-layer test, and Jackson's JSON object mapper. The
tempting conclusion was that this project's Spring Boot version, being recent, must simply
not include full web-testing support, and settling for a plainer test without real HTTP
behavior would have been the easy way out. That would have been the wrong call. Whether a
given class exists, and where, isn't something you have to guess about even for an
unfamiliar or recently changed API. The actual published artifacts are sitting on Maven
Central and can be inspected directly:

```bash
curl -s ".../spring-boot-starter-webmvc-test/4.1.1/spring-boot-starter-webmvc-test-4.1.1.pom" | grep artifactId
curl -sL ".../spring-boot-webmvc-test/4.1.1/spring-boot-webmvc-test-4.1.1.jar" -o x.jar && unzip -l x.jar | grep WebMvcTest.class
```

That found the real answer in a few minutes. The web-test annotation had simply moved to
a new package in this Spring Boot version. And more interesting: Jackson itself had moved
groups entirely, from its long-standing `com.fasterxml.jackson` package to `tools.jackson`,
which matches Jackson's own publicly announced plan for its next major version. Everything
else, `MockMvc` itself and the related test utilities, was exactly where twenty years of
Spring convention would expect it. Only the two things that had actually moved needed
updating. The general point: "I can't find the right import" is a question with a
checkable answer. Go look at the dependency's actual contents. It isn't a sign to lower
your ambitions for the test.

## Lesson twelve: don't let a plausible one-off theory become a documented fact until it's actually checked, especially the one that seems to explain itself

A build occasionally failed at the image-push step because it couldn't find a file the
previous step had just built seconds earlier, in what was supposed to be the same shared
workspace. Retrying the same build usually fixed it. My first explanation was that the
shared storage backing that workspace wasn't reliably available across different nodes,
and that the fix would need to force every step of a build onto the same node. I want to
be upfront that this theory was never actually checked against the failing run itself
(its pods were already gone by the time I looked), and it turned out to be wrong. I had
inferred it from a retry succeeding, which is exactly the kind of unverified but
plausible-sounding conclusion that lesson eleven warns about, and I only caught it this
time because I went ahead and tried to build the actual fix.

Building that fix, forcing pods onto the same node, failed immediately, and the failure
itself was the correction. The CI tool already has a built-in feature that guarantees
exactly that kind of co-scheduling by design: it creates one dedicated helper pod first,
then requires every step of a build to run on the same node as that helper. I confirmed
this was already working correctly the whole time. At that point I still didn't know the
real cause, and I want to be honest that I left it stated as unknown rather than
inventing a new guess to replace the wrong one.

The actual cause, found afterward by finally comparing the start and end times of every
failed build against every other build running at the same time: two builds were sharing
one fixed storage volume with no protection against both writing to it at once. A later
build's own cleanup step was deleting an earlier build's files while that earlier build
was still running. This had been happening because the normal workflow during this
project was to push a change and then immediately trigger a manual test build, which is
exactly the condition that makes the automated trigger and a manual one collide.

The fix was to have the automated trigger check whether a build is already in progress
before starting a new one, and skip that cycle if so. That protects the automated path
completely. It doesn't stop someone from manually starting a second build while one is
already running, which is a real way to reproduce the original bug on demand, and is
exactly how the fix itself got verified.

Three theories, in order, each one dropped only because the evidence contradicted it, not
because a better guess came along: a storage theory that was never checked and turned out
wrong, then a correct realization that a built-in feature already prevented that exact
problem (which meant admitting the real cause was still unknown, rather than replacing one
guess with another), and finally the actual mechanism, found by checking the one thing
that mattered the whole time: whether two builds were ever running at once. The middle
step, saying "I don't know yet" instead of reaching for the next plausible story, is what
kept the final answer from just being a fourth wrong guess.
