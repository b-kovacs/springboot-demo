package com.example.demo;

import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/messages")
public class MessageController {
    private final MessageRepository repo;

    public MessageController(MessageRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public List<Message> all() {
        return repo.findAll();
    }

    @PostMapping
    public Message create(@RequestBody Message message) {
        return repo.save(message);
    }
}