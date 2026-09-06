package com.example.demo;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// @RestController = @Controller + @ResponseBody applied to every method: return values
// are serialized (to JSON, via Jackson, auto-configured because spring-boot-starter-webmvc
// is on the classpath) straight into the HTTP response body, rather than being resolved as
// a view/template name the way a plain @Controller's return value would be in a
// traditional server-rendered app.
//
// @RequestMapping("/messages") at the class level prefixes every handler method below with
// "/messages" - so @GetMapping("/{id}") actually maps to GET /messages/{id}.
@RestController
@RequestMapping("/messages")
public class MessageController {

    // Same constructor-injection reasoning as MessageService: this controller depends on
    // the SERVICE, never on MessageRepository directly - the controller has no idea a
    // database is involved at all, which is exactly the point of the layering.
    private final MessageService service;

    public MessageController(MessageService service) {
        this.service = service;
    }

    // GET /messages -> 200 OK with a JSON array body. No @ResponseStatus needed: 200 is
    // Spring MVC's default for a successful handler method that returns a value.
    @GetMapping
    public List<MessageResponse> all() {
        return service.findAll();
    }

    // @PathVariable binds the {id} segment of the URL to this parameter by name-matching
    // (works via -parameters compiler flag / reflection; can be made explicit with
    // @PathVariable("id") if the parameter name were different from the URL template).
    // If service.findById() throws MessageNotFoundException, MessageExceptionHandler
    // intercepts it below the controller and turns it into a 404 - this method itself
    // only ever has to think about the success path.
    @GetMapping("/{id}")
    public MessageResponse byId(@PathVariable Long id) {
        return service.findById(id);
    }

    // @Valid triggers Bean Validation on the deserialized CreateMessageRequest BEFORE this
    // method body runs at all - a blank "text" field never reaches here (see the
    // @NotBlank note in CreateMessageRequest.java); Spring returns 400 automatically.
    // @ResponseStatus(CREATED) overrides the default 200 with the more semantically
    // correct 201 for a successful resource-creation POST.
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MessageResponse create(@Valid @RequestBody CreateMessageRequest request) {
        return service.create(request);
    }

    // DELETE with no response body and a 204 status - the conventional REST shape for "the
    // resource is gone, there's nothing meaningful left to return." A void return type
    // combined with @ResponseStatus is how you express "no body" in Spring MVC.
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
