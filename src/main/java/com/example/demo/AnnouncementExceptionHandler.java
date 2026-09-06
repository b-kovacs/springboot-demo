package com.example.demo;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

// @RestControllerAdvice applies globally, across every @RestController - one class here
// handles AnnouncementNotFoundException no matter which controller method throws it,
// instead of each controller needing its own try/catch.
@RestControllerAdvice
public class AnnouncementExceptionHandler {

    // Whenever a method in any @RestController lets an AnnouncementNotFoundException
    // escape uncaught, Spring routes it here instead of letting it become an unhandled
    // 500. The returned ResponseEntity's status and body become the actual response.
    @ExceptionHandler(AnnouncementNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(AnnouncementNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }

    // No generic Exception.class handler on purpose: letting a genuinely unexpected
    // error fall through to Spring Boot's default 500 handling is correct. Only
    // exceptions with an intentional business meaning belong here - a catch-all would
    // hide real bugs behind a misleadingly specific-looking response.
}
