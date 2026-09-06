package com.example.demo;

import jakarta.validation.constraints.NotBlank;

public record CreateMessageRequest(@NotBlank String text) {
}
