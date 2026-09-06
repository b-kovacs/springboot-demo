package com.example.demo;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// @RestController = @Controller + @ResponseBody on every method: return values are
// serialized straight into the HTTP response body (as JSON, via Jackson), instead of
// being resolved as a view/template name the way a plain @Controller's return value
// would be in a server-rendered app.
//
// @RequestMapping("/announcements") prefixes every handler below, so
// @GetMapping("/{id}") actually maps to GET /announcements/{id}.
@RestController
@RequestMapping("/announcements")
public class AnnouncementController {

    // Depends on the SERVICE, never the repository directly - this controller has no
    // idea a database is involved at all, which is the point of the layering.
    private final AnnouncementService service;

    public AnnouncementController(AnnouncementService service) {
        this.service = service;
    }

    // GET /announcements -> 200 with a JSON array of every posted announcement.
    @GetMapping
    public List<AnnouncementResponse> all() {
        return service.findAll();
    }

    // If service.findById() throws AnnouncementNotFoundException,
    // AnnouncementExceptionHandler intercepts it and returns a 404 - this method only
    // ever has to think about the success path.
    @GetMapping("/{id}")
    public AnnouncementResponse byId(@PathVariable Long id) {
        return service.findById(id);
    }

    // @Valid runs Bean Validation on the request BEFORE this method body executes - a
    // blank "text" field never reaches here (see @NotBlank on CreateAnnouncementRequest).
    // @ResponseStatus(CREATED) gives the more semantically correct 201 for a successful
    // resource-creation POST, instead of the default 200.
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AnnouncementResponse create(@Valid @RequestBody CreateAnnouncementRequest request) {
        return service.create(request);
    }

    // DELETE with no response body and 204: the conventional REST shape for "the
    // resource is gone, there's nothing left to return."
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
