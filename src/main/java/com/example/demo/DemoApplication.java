package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// @SpringBootApplication is three annotations combined into one:
//   @SpringBootConfiguration - marks this as the app's root @Configuration class
//   @EnableAutoConfiguration - the actual "magic": scans the classpath and conditionally
//       registers beans (e.g. a DataSource + EntityManager appear automatically just
//       because postgresql + spring-boot-starter-data-jpa are on the classpath, with zero
//       manual @Bean methods written for them)
//   @ComponentScan - scans this package and everything under it for @Component/@Service/
//       @Repository/@Controller classes and registers them as beans
@SpringBootApplication
public class DemoApplication {

	public static void main(String[] args) {
		// SpringApplication.run() is where everything actually happens on startup:
		// 1. creates the ApplicationContext (the IoC container)
		// 2. component-scans + auto-configures -> registers every bean definition
		// 3. resolves constructor dependencies between beans and instantiates them in
		//    dependency order (this is where AnnouncementController gets a real AnnouncementService,
		//    which gets a real AnnouncementRepository, injected automatically)
		// 4. starts the embedded servlet container (Tomcat by default) so the app can
		//    actually receive HTTP requests
		SpringApplication.run(DemoApplication.class, args);
	}

}

// A second top-level class in the same file is legal in Java as long as only one is
// public and it matches the filename - kept here rather than split out because it's a
// single trivial endpoint from an early step of this project (the very first proof that
// "the app can serve an HTTP request at all").
@RestController
class VersionController {

	// @GetMapping is shorthand for @RequestMapping(method = RequestMethod.GET, ...).
	// Returning a plain String from a @RestController method (not @Controller) writes it
	// directly to the HTTP response body - no view/template resolution involved, because
	// @RestController = @Controller + @ResponseBody on every handler method.
	@GetMapping("/version")
	public String version() {
		return "CI/CD + Flux image automation end-to-end test";
	}

}
