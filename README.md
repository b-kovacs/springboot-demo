# springboot-demo

A small Spring Boot + JPA REST service — the application half of a larger project. The
other half, [flux-infra](https://github.com/b-kovacs/flux-infra), is a full GitOps-managed
Kubernetes platform: this app is what it builds (via an in-cluster Tekton pipeline,
triggered automatically on every push to this repo), deploys, secures, and monitors.

**Read `flux-infra`'s README first** if you're evaluating the platform work — this repo is
deliberately the smaller half. Two things worth knowing before reading the code itself:

## ⚠️ This project runs a fictional, future Spring Boot version — read this before the code

To exercise a genuinely recent JDK/Maven/Spring toolchain end-to-end, this project pins
`spring-boot-starter-parent` to version **4.1.1** — a version that doesn't exist in the real
world at the time of writing. Two real, consequential things fell out of that choice, and
both are called out with comments at the exact lines they affect:

- **`@WebMvcTest` lives at `org.springframework.boot.webmvc.test.autoconfigure`** in this
  version, not the long-standing `org.springframework.boot.test.autoconfigure.web.servlet`
  most current tutorials/Stack Overflow answers show.
- **Jackson itself relocated** — `com.fasterxml.jackson.databind.ObjectMapper` becomes
  `tools.jackson.databind.ObjectMapper` (groupId `tools.jackson.core`), tracking Jackson's
  own real, publicly-discussed plan for its 3.x line.

Both were found by downloading and inspecting the real published artifacts from Maven
Central rather than guessing — see `MessageControllerTest.java`'s own comments, and
[flux-infra's learning notes](https://github.com/b-kovacs/springboot-demo/blob/main/learning/README.md#the-eleventh-lesson-dont-guess-a-fictionalfuture-frameworks-api--go-read-the-real-published-artifact)
for the full story. **If you're evaluating this for real-world Spring Boot fluency**: the
patterns, layering, and testing approach below are all standard, current practice —
only these two specific import paths are version-specific quirks of this sandbox.

## Why the code is unusually heavily commented

This project doubles as personal interview-prep material, so the normal "don't over-comment,
well-named code speaks for itself" discipline is deliberately relaxed here: every file
explains the Spring concept, annotation, or design decision it demonstrates, in place. If
you're skimming for signal on how I write *production* code, weight the
[flux-infra](https://github.com/b-kovacs/flux-infra) manifests and the layering/testing
*decisions* here more than the comment density itself.

## What's actually in this repo

```
src/main/java/com/example/demo/
├── DemoApplication.java         entry point + the very first endpoint proven working
├── HelloController.java         the second, still-trivial endpoint
├── Message.java                 JPA entity
├── MessageRepository.java       Spring Data JPA - zero implementation code, works anyway
├── MessageService.java          business logic layer, entity<->DTO mapping, the one
│                                 "throw if missing" rule - unit-tested with a mocked
│                                 repository, no Spring context needed at all
├── MessageController.java       thin HTTP adapter: parse request -> call service -> map
│                                 response/exception to a status code, nothing else
├── CreateMessageRequest.java    input DTO, Bean Validation (@NotBlank)
├── MessageResponse.java         output DTO, decoupled from the JPA entity on purpose
└── MessageExceptionHandler.java @RestControllerAdvice - one place, not scattered try/catch
```

This started as a JPA entity exposed directly over REST with no service layer, no DTOs, and
no tests. It was refactored into the layered shape above deliberately — see the commit
history and `flux-infra`'s learning notes for why each piece exists, not just what it does.

## Testing approach

Two distinct styles, on purpose:

- **`MessageServiceTest`** — a pure unit test. No Spring context at all; Mockito supplies a
  fake `MessageRepository` directly to a real `MessageService` instance. Runs in
  milliseconds.
- **`MessageControllerTest`** — a `@WebMvcTest` slice test. Boots only the web layer
  (routing, JSON, validation, the exception handler) with `MockMvc`, mocking the service
  layer below it — real HTTP-level behavior (status codes, JSON body shape) verified
  without a database.
- **`DemoApplicationTests`** — the one full `@SpringBootTest`, existing purely to catch "does
  the app actually start" bugs. Runs against an in-memory H2 database
  (`src/test/resources/application.properties`), not the real Postgres the app actually
  targets in production — see that file's comments for exactly why.

CI (Tekton, in `flux-infra`) runs `mvn clean package` with tests **not** skipped — a
pipeline that skips its own tests isn't testing anything.

## Observability

`/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` (the
Kubernetes-consumed ones), and `/actuator/prometheus` (real JVM/HTTP metrics, scraped by
Prometheus every 15s in the cluster) are all wired and actually used — not just added for
show. See `application.properties` for the exact config and why each line is there.

## Running it

The app needs a reachable Postgres — running the jar alone with no database will fail to
start (Spring's JPA auto-configuration needs a working `DataSource`), which is expected,
not a bug. Locally:

```bash
# a throwaway Postgres matching the defaults in application.properties
docker run -d --name demo-postgres -p 5432:5432 \
  -e POSTGRES_USER=demo -e POSTGRES_PASSWORD=demo -e POSTGRES_DB=demodb postgres:17

./mvnw clean package
java -jar target/demo-0.0.1-SNAPSHOT.jar
# then: curl http://localhost:8080/messages
```

In production this same jar runs unmodified inside the `flux-infra`-managed cluster, with
`DB_HOST`/`DB_USER`/`DB_PASSWORD` supplied by Kubernetes instead of the `localhost`
defaults — see the `${DB_HOST:localhost}` placeholder note in `application.properties`.
