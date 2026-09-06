package com.example.demo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// The very first endpoint added to this project - proof that the whole toolchain (JDK ->
// Maven -> Spring Boot -> embedded Tomcat) actually boots and serves a request. Kept
// around as the simplest possible example of a @RestController: no dependencies injected,
// no request parameters, just a fixed response. Compare to AnnouncementController for what a
// real, layered endpoint looks like once there's actual business logic involved.
@RestController
public class HelloController {
    @GetMapping("/hello")
    public String hello() {
        return "Hello from Spring Boot 4 on JDK 25!";
    }
}
