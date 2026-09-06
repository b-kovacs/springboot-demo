package com.example.demo;

import org.springframework.stereotype.Service;

import java.util.List;

// @Service is functionally the same as @Component (both just register a bean); the
// distinct annotation exists to tell a reader "this is the business logic layer," as
// opposed to @Repository (data access) or @Controller (web layer).
//
// This is where the actual rules live: mapping an entity to a response, and deciding
// what "not found" means. Keeping that here instead of in the controller means:
//   - AnnouncementController stays a thin HTTP adapter with zero business logic
//   - this class is unit-testable with a mocked repository and no Spring context at all
//     (see AnnouncementServiceTest) - fast, no database, no web server
//   - if a second entry point ever needs "post an announcement" (a scheduled digest, an
//     admin CLI), it reuses this class instead of duplicating the rule
@Service
public class AnnouncementService {

    // Constructor injection, not @Autowired on a field: the field can be final, the
    // dependency can't be forgotten, and a test can call
    // `new AnnouncementService(mockRepository)` directly with no Spring container at all.
    private final AnnouncementRepository repository;

    public AnnouncementService(AnnouncementRepository repository) {
        this.repository = repository;
    }

    public List<AnnouncementResponse> findAll() {
        return repository.findAll().stream().map(AnnouncementResponse::from).toList();
    }

    public AnnouncementResponse findById(Long id) {
        return repository.findById(id)
                .map(AnnouncementResponse::from)
                .orElseThrow(() -> new AnnouncementNotFoundException(id));
    }

    public AnnouncementResponse create(CreateAnnouncementRequest request) {
        // save() on a new entity (no id set) performs an INSERT and returns the managed
        // entity with its database-generated id populated - that's why the *returned*
        // `saved` object is mapped, not a fresh `new Announcement(...)`, which would
        // still have a null id.
        Announcement saved = repository.save(new Announcement(request.text()));
        return AnnouncementResponse.from(saved);
    }

    public void delete(Long id) {
        // Checking existsById() first and throwing our own exception is deliberate:
        // deleteById() on a missing id throws Hibernate's own
        // EmptyResultDataAccessException, which our exception handler doesn't know about
        // and would surface as an unintended 500 instead of the intended 404.
        if (!repository.existsById(id)) {
            throw new AnnouncementNotFoundException(id);
        }
        repository.deleteById(id);
    }
}
