package com.example.demo;

import jakarta.validation.constraints.NotBlank;

// The INPUT-side DTO: what a client is allowed to send when creating a Message. A record
// (not a class) because it's an immutable bag of data with no behavior - Java generates
// the constructor, accessors (text(), not getText() - records use the field name itself),
// equals()/hashCode()/toString() for free, so there's nothing left to hand-write.
//
// WHY this exists instead of taking a Message directly in the controller: a client
// creating a message has no business supplying an id or createdAt (those are server-owned
// - see Message's constructor) - the request DTO's shape enforces that by construction,
// not by convention or validation code that has to remember to ignore extra fields.
//
// @NotBlank triggers Bean Validation when the controller method parameter is annotated
// with @Valid (see MessageController.create) - a request with a null, empty, or
// whitespace-only "text" field never reaches the service layer at all; Spring returns
// HTTP 400 automatically before any application code runs.
public record CreateMessageRequest(@NotBlank String text) {
}
