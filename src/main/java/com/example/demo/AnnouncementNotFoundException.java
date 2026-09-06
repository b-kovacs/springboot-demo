package com.example.demo;

// A RuntimeException (unchecked), on purpose, so it can propagate up through
// AnnouncementService and AnnouncementController without every method signature in
// between needing "throws AnnouncementNotFoundException". It's caught in exactly one
// place, AnnouncementExceptionHandler, which turns it into an HTTP 404.
public class AnnouncementNotFoundException extends RuntimeException {
    public AnnouncementNotFoundException(Long id) {
        super("Announcement not found: " + id);
    }
}
