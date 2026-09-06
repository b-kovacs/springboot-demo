# Spring Boot lessons

The platform-side lessons live in the other files in this folder. This one is about the
Java and Spring Boot side specifically: what changed when the app grew from a toy CRUD
stub into something with a real shape, what the three-tier testing strategy actually
means and why it looks the way it does, how mocks fit into that, what CRUD and API design
choices were made and why, and how the app actually gets built and packaged.

## From a JPA entity returned directly, to a layered app with a real use case

The app started as a `Message` entity with a repository and a controller that returned
the entity straight from the database, serialized directly to JSON. That works, but it
has two real problems. First, whatever fields the entity happens to have are whatever the
API returns, so adding an internal-only column, say a soft-delete flag or an audit field,
immediately leaks it to every client with no code change anywhere flagging that. Second,
there was no service layer, so there was nowhere for actual business rules to live, and
nothing meaningfully unit-testable without booting a database.

The fix was to give the app a real use case, a small internal announcements service, and
rebuild it with a proper shape: `Announcement` (the JPA entity) sits behind
`AnnouncementRepository` (data access), `AnnouncementService` (the actual rules,
constructor-injected with the repository), and `AnnouncementController` (a thin HTTP
layer with no business logic in it at all). Two records, `CreateAnnouncementRequest` and
`AnnouncementResponse`, act as the DTOs, the objects that actually cross the API
boundary, so the entity itself is never returned to a client and never accepted directly
from one either.

```
CreateAnnouncementRequest (in)  ->  AnnouncementController  ->  AnnouncementService  ->  AnnouncementRepository  ->  Announcement (entity)
Announcement (entity)  ->  AnnouncementService  ->  AnnouncementResponse (out)  ->  AnnouncementController  ->  client
```

The concrete win from this shape: `AnnouncementService` can be fully unit tested with
Mockito and zero Spring context, so those tests run in milliseconds with no database
involved at all, while `AnnouncementController` gets its own separate test that only
checks HTTP behavior, request validation, and status codes, with the service itself
mocked out. Neither test needs to know how the other layer works internally.

## The three-tier testing strategy, and what mocks are actually for

This app uses three distinct kinds of tests, each checking a different thing, and it is
worth being explicit about why there are three rather than just one:

**Pure unit tests with Mockito**, for `AnnouncementService`. A mock is a fake stand-in
object that pretends to be `AnnouncementRepository` without touching a real database at
all. It is told in advance what to return when a given method is called, so the test can
check the service's own logic in isolation, for example that asking for an id that
doesn't exist actually throws `AnnouncementNotFoundException`, without needing a real
row in a real table to make that true:

```java
@ExtendWith(MockitoExtension.class)
class AnnouncementServiceTest {

    @Mock
    AnnouncementRepository repository;

    @InjectMocks
    AnnouncementService service;

    @Test
    void findByIdThrowsWhenMissing() {
        when(repository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(AnnouncementNotFoundException.class, () -> service.findById(99L));
    }
}
```

No Spring context starts at all here. `@InjectMocks` just calls `new
AnnouncementService(repository)` with the mock plugged in, by hand, essentially. This is
why constructor injection matters for testability: a field annotated with `@Autowired`
instead of being passed through the constructor is much harder to substitute like this
without a full Spring context.

**Slice tests with `@WebMvcTest`**, for `AnnouncementController`. This starts only the
web layer of Spring, meaning the controller, its request mapping, and Bean Validation,
without a real database, a real service implementation, or most of the rest of the
application context. The real `AnnouncementService` is replaced with a mock using
`@MockitoBean`, so the test can control exactly what the service returns and check only
how the controller reacts to it: does a validation failure return 400, does a missing
resource return 404, does a successful creation return 201 with the right body:

```java
@WebMvcTest(AnnouncementController.class)
class AnnouncementControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    AnnouncementService service;

    @Test
    void createReturns201() throws Exception {
        when(service.create(any())).thenReturn(new AnnouncementResponse(1L, "hi", Instant.now()));
        mockMvc.perform(post("/announcements")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"hi\"}"))
            .andExpect(status().isCreated());
    }
}
```

`MockMvc` here simulates an actual HTTP request going through Spring's web layer, without
a real network socket or a real running server. It is a different kind of double than
`@MockitoBean`: `MockMvc` fakes the transport, `@MockitoBean` fakes a collaborator class.

**One full `@SpringBootTest`**, against a real embedded H2 database instead of Postgres.
This is the only test that actually boots the entire application context end to end,
including real JPA and Hibernate wiring, to catch anything the two faster test tiers
would miss, for example a mapping annotation that is technically wrong but happens to
compile fine. Running this against a real Postgres in CI would mean CI needs an actual
database container available, plus the startup time of connecting to it, for every single
test run. Overriding the datasource just for tests avoids all of that:

```properties
# src/test/resources/application.properties, only applies during tests
spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1
spring.datasource.driver-class-name=org.h2.Driver
spring.jpa.hibernate.ddl-auto=create-drop
```

The rule of thumb this project settled on: reach for the cheapest tier of test that can
actually prove the thing you care about. Business logic gets a plain unit test. HTTP
behavior gets a slice test. Only the wiring between JPA and a real relational database
gets the expensive, full-context test, and only once, not for every scenario.

## CRUD and API design choices, and why

A few small decisions here were deliberate rather than defaults:

`@ResponseStatus(HttpStatus.CREATED)` on the create endpoint, giving a 201 instead of
Spring's default 200 for a POST. The convention for "a resource was created" is 201, and
following that convention matters if anything downstream, a client library, an API
documentation tool, actually depends on standard HTTP semantics rather than just the
response body.

`@Valid` plus Bean Validation's `@NotBlank` on `CreateAnnouncementRequest`, so a blank
`text` field never reaches the controller's method body at all. Spring validates the
request before the handler runs and returns a 400 automatically if it fails, which means
the controller method itself never has to contain an `if (text == null || text.isBlank())`
check.

`@RestControllerAdvice` for exception handling, in `AnnouncementExceptionHandler`, rather
than a try/catch in every controller method that could throw. One class maps
`AnnouncementNotFoundException` to a 404 everywhere in the app it might occur, and
deliberately has no catch-all handler for `Exception.class`. A truly unexpected error is
allowed to fall through to Spring's own default 500 handling. Only exceptions with an
actual, intended business meaning get a specific handler here. A catch-all would risk
hiding a real bug behind a response that looks deliberate but isn't.

Checking `existsById()` before calling `deleteById()` in `AnnouncementService.delete()`,
rather than just calling `deleteById()` directly and catching whatever it throws.
Deleting a missing id with `deleteById()` alone throws Hibernate's own
`EmptyResultDataAccessException`, which the exception handler above has no mapping for,
so it would have surfaced as an unintended 500 instead of the intended 404.

Verified with a real run against the deployed app, not just by reading the code:

```
$ curl -s -X POST http://localhost:8080/announcements -H "Content-Type: application/json" -d '{"text":"office closed Monday"}'
{"id":1,"text":"office closed Monday","createdAt":"2026-09-06T15:50:26.493888853Z"}

$ curl -s http://localhost:8080/announcements
[{"id":1,"text":"office closed Monday","createdAt":"2026-09-06T15:50:26.493888853Z"}]
```

## Finding a relocated API instead of guessing at it

Writing the `@WebMvcTest` slice test above hit an import that did not resolve where years
of Spring Boot convention would suggest it should. Rather than guessing, or downgrading
the Spring Boot version just to avoid the question, the actual published jars on Maven
Central were inspected directly:

```
$ curl -s ".../spring-boot-webmvc-test/<ver>/spring-boot-webmvc-test-<ver>.jar" -o x.jar
$ unzip -l x.jar | grep WebMvcTest.class
   org/springframework/boot/webmvc/test/autoconfigure/WebMvcTest.class
```

That confirmed `@WebMvcTest` had genuinely moved, in this Spring Boot version, to
`org.springframework.boot.webmvc.test.autoconfigure`, away from the long-standing
`org.springframework.boot.test.autoconfigure.web.servlet`. The same kind of check on
Jackson's own jar showed it had relocated entirely too, from
`com.fasterxml.jackson.databind.ObjectMapper` to `tools.jackson.databind.ObjectMapper`,
under a new groupId, `tools.jackson.core`, matching Jackson's own publicly planned 3.x
migration. Meanwhile `MockMvc`, `@MockitoBean`, and the request and result matcher
builders were all exactly where long-standing Spring convention would expect them.

The lesson worth keeping: when an unfamiliar or recently updated library does not import
where documentation or muscle memory expects, check the real artifact before assuming the
documentation is right, the version is wrong, or the import is a typo. It took a couple of
minutes and settled the question with certainty instead of a guess.

## How the app actually gets built and packaged

Two things went wrong here before landing on the reliable setup, both from trying to be
cleverer than necessary.

The first attempt hand-extracted the jar into separate layers, thinking a manually
layered image would build faster on repeat pulls. The resulting image was broken: Spring
Boot's own loader class ended up outside the layer the JVM actually looked in, so the
container failed at startup with a ClassNotFound error before the app ever got a chance
to run. The fix was to stop hand-rolling this and just run the ordinary fat jar Maven
already produces:

```dockerfile
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY target/demo-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

The second was a slow build, close to four minutes per Tekton run, entirely spent on
Maven re-downloading every dependency from scratch each time. Pointing Maven's local
repository at the same persistent workspace Tekton already provides for the build fixed
that:

```yaml
script: |
  mvn clean package -B -Dmaven.repo.local=$(workspaces.source.path)/.m2
```

That dropped a warm build to roughly 16 to 18 seconds, confirmed with real timing data
from a Tekton TaskRun, not an estimate:

```
$ kubectl get taskrun build-app-run-qnkzr-build -n default \
    -o jsonpath='{.status.startTime}{"\n"}{.status.completionTime}'
2026-09-05T14:22:03Z
2026-09-05T14:22:19Z
```

And, separately from build speed, `-DskipTests` was removed from the CI command
entirely. A test suite that never actually runs in CI is not providing any real
protection, no matter how good the tests themselves look. The full pipeline, tests
included, was verified end to end with a real build after the `Message` to
`Announcement` rename:

```
$ kubectl get pipelinerun build-app-run-qnkzr -n default -o jsonpath='{.status.conditions[0]}'
{"type":"Succeeded","status":"True","reason":"Succeeded", ...}
```

## What this app taught, in one paragraph

A CRUD app is a small enough surface to actually see the value of each layer clearly:
DTOs are what stop an internal detail from silently becoming a public contract, mocks are
what let you test a decision without needing everything that decision eventually talks
to, and a small, honest test pyramid, mostly fast unit tests, some slice tests, one real
end-to-end test, is worth more than either extreme: all mocks with nothing real ever
checked, or nothing but slow, full-context tests that make you avoid writing more of
them.
