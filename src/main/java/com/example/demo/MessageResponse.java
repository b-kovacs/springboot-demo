package com.example.demo;

import java.time.Instant;

public record MessageResponse(Long id, String text, Instant createdAt) {
    public static MessageResponse from(Message message) {
        return new MessageResponse(message.getId(), message.getText(), message.getCreatedAt());
    }
}
