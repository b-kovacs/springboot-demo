# What Went Badly

The friction, dead ends, and mostly environmental walls that cost the most time, and what
to do differently next time.

## The recurring theme: WSL and rootless networking

This was the single biggest source of pain. Running rootless containers inside WSL2 means
there's no clean network path from outside the machine, through WSL, into a container, and
this wall got hit over and over.

MetalLB (the tool that hands out real IP addresses to Kubernetes load balancers) assigned
IPs correctly, but they weren't reachable from the WSL host itself, only from inside the
cluster. The load balancer technically worked, but you couldn't open it in a browser.
Reaching the app's gateway ended up needing `kubectl port-forward` (tunneling a local port
straight through the Kubernetes API server) because there was no other inbound path at all.

WSL also has a "mirrored networking" mode meant to make `localhost` behave the same on
Windows and inside WSL. Turning it on broke loopback connections inside WSL itself:
`127.0.0.1` got refused even with a server actively listening on it. Went back to the
default NAT networking mode instead.

And anything relying on a webhook, like Tekton Triggers, Argo Events, or a real GitHub
webhook, is simply impossible without a tunnel, because GitHub has no way to reach a
laptop sitting behind WSL and NAT.

The takeaway: rootless `kind` on WSL is great for learning, but it fights you on anything
that needs to be reached from outside, or needs the outside world to reach in. For
anything that needs to run always-on or be reachable externally, a real Linux VM or a
cloud cluster is the right tool. Pull-based patterns, like Flux polling Git or a
self-hosted runner polling outbound, are the workaround that actually fits WSL.

## Things that don't survive a WSL restart or a cluster recreation

`kind` clusters are ephemeral by nature. Shutting down WSL leaves the node containers in a
broken state with a dead API server, so the normal workflow is to recreate the cluster,
not resume it. Worse, several things had to be manually redone after every recreate,
because they had never actually been committed to Git:

- The `tekton-build` ServiceAccount and the `git-credentials` secret
- The `registry-local` Service and its endpoint, needed for in-cluster DNS resolution
- The containerd configuration that lets nodes pull from the local registry over plain
  HTTP
- `postgres-secret`
- A couple of file permissions (`setcap` on two specific binaries) that don't survive a
  WSL restart on their own

This was the deepest lesson from this whole project, learned the hard way: every one of
these should have been in Git or in `kind-cluster.yaml` from the start, and each one
blocked progress until it was manually recreated. "It worked before" means nothing if
"before" was a manual step on a cluster that no longer exists.

## Cold-start slowness that looks exactly like failure

On a completely fresh cluster, every single image has to be pulled from the internet, and
some of the heavier stacks (MetalLB's routing daemon, Tekton, Envoy) took several minutes
each. This repeatedly looked like a hang or a real failure (a Flux timeout, a generic
"context deadline exceeded," pods stuck in `ContainerCreating`) when it was actually just
slow. Time got lost twice over: once waiting, and again by intervening on things that
would have sorted themselves out on their own.

## The layered-jar optimization was a dead end

Spring Boot supports splitting a jar into layers so a container image only needs to
re-upload the layer that actually changed, instead of the whole jar every time. Trying
this produced an image that didn't run at all: the layer containing Spring Boot's own
loader classes came out empty, so the JVM couldn't find the class it needed to start the
app, even though the jar itself was perfectly valid. After chasing this through several
Dockerfile variations, the simple, reliable path won: copy the whole jar and run it with
`java -jar`, which always works because the loader is bundled directly inside the jar.

The takeaway: layered jars are an optimization for image push size, not build speed. The
actual build-speed win came from caching Maven's dependencies, which is a completely
separate thing. It's worth getting the simple, reliable version working first, and only
reaching for a more fragile optimization afterward, and even then, actually confirming the
optimized image runs, not just that it builds.

## Ordering problems in Flux and Helm, all self-inflicted

A few different layering mistakes, all in the same family:

- Bundling a custom resource's configuration together with the tool that installs that
  custom resource, in one atomic step. Concretely: MetalLB's IP address pool needs a
  custom resource type that MetalLB's own install creates. Putting both in the same Flux
  layer meant the config's dry-run check failed, since the custom resource type didn't
  exist yet, which blocked the whole layer, install included. Fixed by giving the config
  its own separate layer that explicitly waits for the install to finish first.
- The top-level Flux configuration was scanning a folder and applying every file inside it
  directly, instead of pointing at separate named layers. That flattened out the ordering
  between layers entirely. Fixed with an explicit file listing only the layer definitions,
  so their declared dependencies actually get respected.
- Helm installs default to a five-minute timeout, which is too short for a slow, cold
  install on a fresh cluster. Had to raise it to fifteen minutes and allow a couple of
  retries.
- One layer was forcing every resource into the `default` namespace, which broke anything
  that needed to live in its own specific namespace, like MetalLB's own components.

## The chase for one working pod, near the end

Getting a single app pod running, near the end of one long stretch, turned into a long
chain of "recreate the missing manual piece, retry, hit the next missing piece." This was
death by a thousand small cuts, and it was really just a symptom of the uncommitted-state
problem above, made worse by simple fatigue. In hindsight, the better move would have been
to stop, commit whatever was in progress, and come back with a clear head to close every
gap in one deliberate pass, instead of trying to push through it manually one piece at a
time.

## Making something declarative introduced a new, subtler bug

Bringing a couple of configuration files under Nix's management, specifically to close the
gap of them not being tracked in Git at all, quietly broke them. Nix does this by
replacing the real file with a symlink pointing into its own internal storage location.
`kind` only mounts the one specific folder a node is told to mount, not the whole storage
location a symlink inside that folder might point to. From inside a freshly created node,
the symlink target simply wasn't there, so the container tool found no configuration and
silently fell back to a default that didn't work.

Only the full rebuild test caught this. Reading the change itself would not have, because
the file looked completely fine, with the right content, right up until something tried to
actually follow the symlink from a different environment.

The takeaway: turning a plain file into a symlink, which is what most declarative
configuration tools do under the hood, is not a harmless change if anything downstream
cares about the file being a real, self-contained file rather than just something with the
right content at the right path. That includes bind mounts, container images, and anything
else that copies or mounts by path instead of reading through a symlink first. Worth
checking for this specific failure mode any time "make it declarative" is the fix being
applied.

## A script that silently swallowed a failure instead of stopping

The scheduled job that checks for new commits and triggers a build had two separate,
individually minor bugs: no credentials for the private repository it was checking, and a
missing permission needed to save its own progress. Either one alone would have just
caused a visible error. Together they were worse. The command that checks the latest
commit failed silently and returned nothing. A fallback meant for a completely different
situation (no saved state yet, on the very first run) quietly accepted that empty result
too. An empty value never matched the previously saved one, so the script concluded there
was a new commit and triggered a build. Every single cycle. For about half an hour,
unnoticed, and the final step meant to save the new state also failed because of the
missing permission, so nothing ever settled. It was caught by watching how many pipeline
runs were piling up, not by reading the script and spotting the bug.

The general point: in any automation that takes an action rather than just reporting
something, it's safer to fail loudly and do nothing than to silently fall back to a value
that makes the "go ahead and act" path look correct. A fallback that swallows an error is
fine for a genuinely optional read. It's dangerous for the one read whose failure should
stop the whole run.

## Two separate DNS systems looked like one, until they didn't

Both the CI pipeline's pods and the deployed app itself could resolve the local
registry's hostname without any trouble, which made it easy to assume it was just an
ordinary Kubernetes Service, resolvable from anywhere. It's actually two unrelated
mechanisms that happened to overlap. Pods resolve it through Kubernetes' own internal DNS,
scoped to the Service's namespace. The nodes themselves, when pulling a container image,
resolve it through the container runtime's own network, completely unaware Kubernetes DNS
even exists. When a component watching the registry from a different namespace couldn't
resolve it, the tempting fix, switching to the fully qualified Kubernetes name, would have
fixed that one component while silently breaking every node's ability to pull images,
since the container runtime's own DNS has no concept of a Kubernetes Service's full name.
Caught by working out exactly which specific consumer was actually broken before changing
anything, instead of generalizing from "this is a DNS problem, so use the full name."

## Both containers had been running as root the whole time

Neither the app nor the database ever had a security context set, until a dedicated
hardening pass done well after both had been rebuilt, restarted, and rebuilt from scratch
several times. Running as root doesn't announce itself anywhere obvious. A normal pod
listing looks identical, the app works fine, health checks pass. It only surfaced from one
deliberate check of who a process was actually running as inside the container. Fixing the
database specifically cost real extra work, since its data directory had already been
written to as root, so switching it to run as a non-root user needed a one-time setup step
to fix file ownership first. Doing it correctly from the very first deploy would have cost
nothing at all.

## Giving a container access to a secret doesn't mean the specific tool inside it reads it correctly

The database backup job pulled in a secret using a shortcut that sets environment
variables named exactly after the secret's own keys, `POSTGRES_USER`, `POSTGRES_PASSWORD`,
and so on. The actual backup tool, `pg_dump`, specifically looks for a variable named
`PGPASSWORD`, not `POSTGRES_PASSWORD`. The job ran, tried to connect, and failed cleanly
with an authentication error rather than doing nothing silently, which was at least easy to
diagnose. But it's a good reminder that "the secret is available to this container" and
"the specific tool running inside it knows to look for it under this exact name" are two
different claims, and the only way to be sure is to actually run the command, not just
read the deployment configuration.

## A monitoring rule silently matched nothing, with no explanation anywhere

Wiring Prometheus up to scrape the app looked complete: matching labels on the rule, a
Service that already routed correctly, a port name that lined up. Nothing produced an
error, not in the rule, not in the Prometheus Operator's own logs, not anywhere. The
target simply never showed up as scraped at all. The actual cause: the rule matches a
Service by its own labels, not by the selector that controls which pods it routes traffic
to, and neither object's configuration says that distinction out loud. It's an easy
mistake to make by accident, since a lot of example manifests happen to set the same label
in both places without ever explaining why both matter. Found only by reading Prometheus's
own live, generated configuration for that specific job and noticing the target being
discovered and then quietly dropped by an internal rule, not by rereading the YAML files
themselves.

## Almost gave up on a real test instead of reading the actual dependency

A test using MockMvc failed with two "this doesn't exist" errors, in an unfamiliar,
recently updated Spring Boot version. The fast, plausible-sounding explanation was that
this project's differently named test dependency must simply not include full web-layer
testing support, and rewriting the test as a plainer substitute felt like the easy way
out. That would have been the wrong call, and permanently shipped a weaker test for a
problem that was actually just two specific classes having moved to a new location,
findable in a few minutes by looking inside the real published files on Maven Central. The
lesson, in hindsight: that plausible explanation was never actually checked against any
evidence. It just happened to explain the symptom well enough to feel true.

## A build failure got blamed on the wrong thing, and the wrong fix proved it

A build occasionally failed at the image-push step because it couldn't find a file the
previous step had just built, despite both steps supposedly sharing one workspace. The
first explanation was that the underlying storage type used for that shared workspace
isn't reliably available across different nodes, and that a fix would need to force every
step onto the same node. That explanation was never actually checked against a real
failing run, since its pods were already gone by the time anyone looked, and it turned out
to be wrong. Building a fix for it failed immediately, and revealed that the CI tool
already has a built-in feature guaranteeing exactly that kind of same-node scheduling, on
by default. Node placement was never the actual problem.

The real cause, found afterward by comparing every failed build's timing against every
other build running at the same time: two builds were sharing one fixed storage volume
with no protection against both writing to it at once, so a later build's own cleanup step
was deleting an earlier build's files while it was still running. Fixed by having the
automated trigger check whether a build is already running before starting a new one. See
the twelfth lesson in `README.md` for the full three-theory version of this story, since
it's a good example of dropping a wrong theory instead of doubling down on it.

## Small but recurring annoyances

- The terminal's shell treating a pasted line starting with `#` as a command instead of a
  comment, and complaining that `#` isn't a valid command.
- Pasting a placeholder like `http://<EXTERNAL-IP>` literally, which the shell
  misinterprets because of the angle brackets.
- A log-streaming command that stops at the boundary between pipeline steps, which looks
  like it hung when it's actually just waiting to be reattached to the next step.
- Pods stuck in a fast restart loop churn too quickly to read their logs normally. The fix
  that worked every time: scale the deployment to zero, run the same image as a single,
  non-restarting pod, and read its log once, calmly.
