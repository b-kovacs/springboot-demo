package com.example.demo;

// A RuntimeException (unchecked), not a checked Exception - deliberately, so it can
// propagate up through MessageService and MessageController without every intermediate
// method signature needing "throws MessageNotFoundException". It's caught in exactly one
// place: MessageExceptionHandler, which turns it into an HTTP 404. This is the standard
// Spring pattern for "expected, meaningful business error" -> "specific HTTP status," kept
// separate from actual bugs/infrastructure failures (which propagate as 500s by default).
public class MessageNotFoundException extends RuntimeException {
    public MessageNotFoundException(Long id) {
        super("Message not found: " + id);
    }
}
