package com.example.demo;

import java.time.Instant;

// The OUTPUT-side DTO: the exact shape returned to a client, decoupled from the Message
// JPA entity (see the note in Message.java for why that decoupling matters). Every field
// here is something safe and meaningful to expose - if Message later grows an internal
// field (an audit column, an internal status flag), it does NOT automatically leak into
// the API response, because this record has to be updated explicitly to expose it.
public record MessageResponse(Long id, String text, Instant createdAt) {

    // A static factory method, not a constructor on Message itself - keeps the
    // entity<->DTO mapping logic in ONE place (here) rather than scattered across every
    // call site that needs to convert a Message to a response. MessageService uses this
    // both for a single Message (findById, create) and via a method reference for a whole
    // list (findAll: .stream().map(MessageResponse::from)).
    public static MessageResponse from(Message message) {
        return new MessageResponse(message.getId(), message.getText(), message.getCreatedAt());
    }
}
