package com.example.demo;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MessageService {

    private final MessageRepository repository;

    public MessageService(MessageRepository repository) {
        this.repository = repository;
    }

    public List<MessageResponse> findAll() {
        return repository.findAll().stream().map(MessageResponse::from).toList();
    }

    public MessageResponse findById(Long id) {
        return repository.findById(id)
                .map(MessageResponse::from)
                .orElseThrow(() -> new MessageNotFoundException(id));
    }

    public MessageResponse create(CreateMessageRequest request) {
        Message saved = repository.save(new Message(request.text()));
        return MessageResponse.from(saved);
    }

    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new MessageNotFoundException(id);
        }
        repository.deleteById(id);
    }
}
