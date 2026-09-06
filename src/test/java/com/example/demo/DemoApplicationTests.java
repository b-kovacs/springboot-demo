package com.example.demo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// @SpringBootTest boots the ENTIRE application context - every bean, including the real
// DataSource/EntityManager - unlike MessageServiceTest (no Spring at all) or
// MessageControllerTest's @WebMvcTest (web layer only). This is the slowest and heaviest
// of the three test styles, which is why there's only ONE such test in this whole project:
// it exists purely to catch "does the application actually start" bugs (a missing bean, a
// broken @Configuration, an unsatisfiable dependency) - not business logic, which belongs
// in the faster, more targeted tests instead.
//
// contextLoads() has an empty body ON PURPOSE: the test PASSES simply by not throwing
// during Spring context startup. If any bean fails to wire, this test fails with the real
// underlying exception, which is the whole point.
//
// WHY THIS TEST NEEDS ITS OWN DATABASE CONFIG: booting the full context means booting the
// real JPA/Hibernate configuration too, which needs an actual reachable database - there's
// no Postgres in the CI build environment, so src/test/resources/application.properties
// overrides the datasource to an in-memory H2 database for the test classpath only (test
// resources take priority over main resources when running tests). Without that override,
// this single test would fail every CI build with a Hibernate "can't determine dialect"
// error, unrelated to any actual code bug - see
// ~/projects/springboot-demo/learning/04-solutions-reference.md for the exact incident.
@SpringBootTest
class DemoApplicationTests {

	@Test
	void contextLoads() {
	}

}
