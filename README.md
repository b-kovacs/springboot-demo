# springboot-demo

A small internal announcements service, built with Spring Boot and JPA. Teams post short
announcements ("office closed Monday", "new deploy process starts next sprint") and anyone
can read them through a REST API, instead of announcements getting lost in chat.

This is one half of a two-repo project. The other half,
[flux-infra](https://github.com/b-kovacs/flux-infra), is a full Kubernetes platform
managed with GitOps. It builds this app through an automated pipeline, deploys it,
secures it, and monitors it. Both halves matter equally, and both are kept to the basics
on purpose: enough Spring Boot to be a real, properly layered, properly tested service,
and enough Kubernetes to be a real, properly automated platform, without either side
growing beyond what's needed to actually learn the fundamentals well.

## A quick note on the Spring Boot version

This project uses Spring Boot 4.1.1, the current version at the time of writing. It's
recent enough that two things moved from where most tutorials and Stack Overflow answers
still show them: `@WebMvcTest` now lives at
`org.springframework.boot.webmvc.test.autoconfigure` instead of the older
`org.springframework.boot.test.autoconfigure.web.servlet`, and Jackson itself moved from
the `com.fasterxml.jackson` package to `tools.jackson`, matching Jackson's own public plan
for its next major version. Both are called out in comments at the exact lines they
affect, in `AnnouncementControllerTest.java`. I found the correct locations by downloading
and inspecting the actual published Maven artifacts rather than guessing. Everything else,
the layering, the testing approach, the patterns below, is standard, current Spring Boot
practice.

## Why the code is heavily commented

This project doubles as study material for me, so the usual "don't over-comment, let
well-named code speak for itself" rule is relaxed here on purpose. Every file explains the
Spring concept or design decision it demonstrates, right where it happens. The
`flux-infra` manifests are commented the same way and for the same reason, so comment
density on its own isn't a useful signal of how either of us would write production code
day to day. A better signal is the actual decisions underneath the comments: why a DTO
exists instead of returning the entity directly, why the service layer is unit-tested
with no Spring context involved at all, why one Kustomization layer waits on another
instead of everything being applied at once. Those choices would still be there with
every comment stripped out.

## What's in this repo

```
src/main/java/com/example/demo/
├── DemoApplication.java             entry point, plus the first endpoint I got working
├── HelloController.java             the second, still trivial, endpoint
├── Announcement.java                JPA entity
├── AnnouncementRepository.java      Spring Data JPA, no implementation code needed
├── AnnouncementService.java         business logic and entity-to-response mapping,
│                                    unit tested with a mocked repository, no Spring
│                                    context needed
├── AnnouncementController.java      thin HTTP layer: parse the request, call the
│                                    service, map the result to a status code
├── CreateAnnouncementRequest.java   input DTO, validated with @NotBlank
├── AnnouncementResponse.java        output DTO, kept separate from the JPA entity
└── AnnouncementExceptionHandler.java   turns a not-found exception into a 404,
                                     in one place instead of scattered try/catch blocks
```

This started as a JPA entity exposed directly over REST, with no service layer, no DTOs,
and no tests. I refactored it into the layered shape above on purpose. The commit history
and `flux-infra`'s learning notes explain why each piece exists, not just what it does.

## How it's tested

Three different styles, each for a different reason:

- `AnnouncementServiceTest` is a plain unit test. No Spring involved at all. Mockito hands
  a fake repository to a real service object directly. Runs in milliseconds.
- `AnnouncementControllerTest` is a slice test, using `@WebMvcTest`. It boots only the web
  layer (routing, JSON conversion, validation, the exception handler) and uses `MockMvc`
  to send real HTTP-shaped requests, while mocking the service underneath. This checks
  actual status codes and response bodies without touching a database.
- `DemoApplicationTests` is the one full `@SpringBootTest`. It boots everything, and
  exists purely to catch "does the app actually start" problems. It runs against an
  in-memory H2 database instead of the real Postgres the app uses in production. See
  `src/test/resources/application.properties` for why.

The CI pipeline in `flux-infra` runs `mvn clean package` with tests included, not skipped.
A pipeline that skips its own tests isn't really testing anything.

## Observability

The app exposes `/actuator/health`, plus separate liveness and readiness checks that
Kubernetes uses to decide whether to restart it or just stop sending it traffic, and
`/actuator/prometheus`, which Prometheus scrapes every 15 seconds for real JVM and HTTP
metrics. None of this is decorative. See `application.properties` for the exact
configuration and why each line is there.

## Running it locally

The app needs a reachable Postgres. Running the jar with no database available will fail
to start, since Spring's JPA setup needs a working connection. That's expected, not a bug.

```bash
# a throwaway Postgres matching the defaults in application.properties
docker run -d --name demo-postgres -p 5432:5432 \
  -e POSTGRES_USER=demo -e POSTGRES_PASSWORD=demo -e POSTGRES_DB=demodb postgres:17

./mvnw clean package
java -jar target/demo-0.0.1-SNAPSHOT.jar
# then: curl http://localhost:8080/announcements
```

In production, the same jar runs unmodified inside the `flux-infra`-managed cluster.
Kubernetes supplies `DB_HOST`, `DB_USER`, and `DB_PASSWORD` instead of the `localhost`
defaults. See the `${DB_HOST:localhost}` placeholder in `application.properties` for how
that switch happens with no code change.
