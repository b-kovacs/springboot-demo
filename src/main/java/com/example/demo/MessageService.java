package com.example.demo;

import org.springframework.stereotype.Service;

import java.util.List;

// @Service is functionally identical to @Component (both just register a bean) - the
// distinct annotation exists purely to communicate INTENT to a reader: "this is
// business-logic layer," as opposed to @Repository (data access) or @Controller (web
// layer). Spring itself doesn't treat @Service specially.
//
// WHY this class exists at all, given MessageRepository already has save()/findById()/etc.:
// this is the layer that used to be missing entirely (see git history - the original
// version of this file's neighbors had MessageController calling MessageRepository
// directly). Putting the entity<->DTO translation and the "throw if missing" business rule
// HERE means:
//   - the controller stays a thin HTTP adapter (parse request -> call service -> map
//     response/exception to a status code) and contains zero business logic
//   - this logic is unit-testable with a MOCKED repository and no Spring context at all
//     (see MessageServiceTest) - fast, no database, no web server needed
//   - if a second entry point ever needs "create a message" (a CLI, a message-queue
//     listener), it reuses this class instead of duplicating the logic in a second
//     controller
@Service
public class MessageService {

    // Constructor injection, not @Autowired on a field - see the Spring Boot basics
    // notes in ~/interview-prep/spring/01-spring-boot-basics.md for the full "why," but
    // briefly: this field can be `final` (immutable once constructed), the dependency is
    // impossible to forget (the class literally cannot be constructed without one), and a
    // unit test can call `new MessageService(mockRepository)` directly with no Spring
    // container involved at all.
    private final MessageRepository repository;

    public MessageService(MessageRepository repository) {
        this.repository = repository;
    }

    public List<MessageResponse> findAll() {
        // .toList() (Java 16+) is a shorthand for .collect(Collectors.toUnmodifiableList())
        // - returns an immutable list, appropriate for a read-only API response.
        return repository.findAll().stream().map(MessageResponse::from).toList();
    }

    public MessageResponse findById(Long id) {
        // Optional<Message> from the repository is turned into either a mapped
        // MessageResponse or a thrown exception - never a null returned to the caller.
        // .orElseThrow(supplier) only constructs the exception if the Optional is
        // actually empty, avoiding an unnecessary allocation on the success path.
        return repository.findById(id)
                .map(MessageResponse::from)
                .orElseThrow(() -> new MessageNotFoundException(id));
    }

    public MessageResponse create(CreateMessageRequest request) {
        // repository.save() on a NEW entity (no id set) performs an INSERT and returns
        // the managed entity with its database-generated id populated - that's why we map
        // the *returned* `saved` object, not a fresh `new Message(...)`, which would still
        // have a null id.
        Message saved = repository.save(new Message(request.text()));
        return MessageResponse.from(saved);
    }

    public void delete(Long id) {
        // Checking existsById() first, and throwing our own MessageNotFoundException, is
        // deliberate: JpaRepository.deleteById() on a missing id throws Hibernate's own
        // EmptyResultDataAccessException, which isn't handled by our
        // @RestControllerAdvice and would surface as an unintended 500 instead of the
        // intended 404.
        if (!repository.existsById(id)) {
            throw new MessageNotFoundException(id);
        }
        repository.deleteById(id);
    }
}
