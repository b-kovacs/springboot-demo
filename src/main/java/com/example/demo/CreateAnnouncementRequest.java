package com.example.demo;

import jakarta.validation.constraints.NotBlank;

// The input side: what a client sends when posting a new announcement. A record, not a
// class, because it's an immutable bag of data with no behavior of its own - Java
// generates the constructor, accessors, equals/hashCode/toString for free.
//
// A client posting an announcement has no business supplying an id or a timestamp (those
// are owned by the server, see Announcement's constructor). This record's shape enforces
// that by construction, not by a validation rule that has to remember to ignore extra
// fields.
//
// @NotBlank is checked when the controller parameter is annotated with @Valid (see
// AnnouncementController.create): a request with empty or missing text never reaches the
// service at all. Spring returns 400 automatically, before any application code runs.
public record CreateAnnouncementRequest(@NotBlank String text) {
}
