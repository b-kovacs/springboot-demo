package com.example.demo;

import java.time.Instant;

// The output side: the exact shape returned to a client, kept separate from the
// Announcement JPA entity (see the note in Announcement.java for why). If the entity
// later grows an internal-only field, it does not automatically leak into the API
// response - this record has to be updated on purpose to expose anything new.
public record AnnouncementResponse(Long id, String text, Instant createdAt) {

    // A static factory, not a constructor on Announcement itself - keeps the
    // entity-to-response mapping in one place instead of scattered across call sites.
    // AnnouncementService uses this both for a single announcement and, via a method
    // reference, for a whole list.
    public static AnnouncementResponse from(Announcement announcement) {
        return new AnnouncementResponse(announcement.getId(), announcement.getText(), announcement.getCreatedAt());
    }
}
