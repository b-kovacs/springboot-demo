package com.example.demo;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

// @RestControllerAdvice applies globally, across every @RestController in the application
// - one class here handles MessageNotFoundException no matter which controller method
// throws it, instead of each controller method needing its own try/catch. This is the
// standard Spring pattern for centralizing "business exception -> HTTP response" mapping,
// keeping that concern completely out of the controllers themselves.
@RestControllerAdvice
public class MessageExceptionHandler {

    // @ExceptionHandler(MessageNotFoundException.class) means: whenever a method in ANY
    // @RestController lets a MessageNotFoundException escape uncaught, Spring routes it
    // here instead of letting it become an unhandled 500. The returned ResponseEntity's
    // status AND body become the actual HTTP response sent to the client.
    @ExceptionHandler(MessageNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(MessageNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }

    // Deliberately NOT handling every possible exception here (e.g. no generic
    // Exception.class handler): letting genuinely unexpected errors fall through to
    // Spring Boot's default error handling (a 500, with a generic body in production) is
    // correct - only exceptions with a clear, INTENTIONAL business meaning belong in a
    // handler like this one. A blanket catch-all would hide real bugs behind a
    // misleadingly specific-looking response.
}
