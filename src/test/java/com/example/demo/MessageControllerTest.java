package com.example.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// A SLICE TEST, not a unit test and not a full integration test: @WebMvcTest boots only
// the web layer of the Spring context (MessageController + Spring MVC infrastructure -
// JSON conversion, validation, exception handling), NOT the JPA/database layer. This is
// the middle ground between MessageServiceTest's zero-Spring unit tests and a full
// @SpringBootTest that would boot everything including a real DataSource.
//
// NOTE ON PACKAGE NAMES: @WebMvcTest lives at org.springframework.boot.webmvc.test
// .autoconfigure and ObjectMapper at tools.jackson.databind in THIS project's Spring Boot
// version specifically - both moved from their long-standing classic locations
// (org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest and
// com.fasterxml.jackson.databind.ObjectMapper) in a later Spring Boot release than what
// most tutorials/Stack Overflow answers were written against. See
// ~/interview-prep/spring/01-spring-boot-basics.md for the REAL, current package
// locations to use in an actual interview - don't cite these paths there.
@WebMvcTest(MessageController.class)
class MessageControllerTest {

    // MockMvc simulates HTTP requests against the controller WITHOUT starting a real
    // server socket - no port bound, no actual network involved, but the full Spring MVC
    // dispatch pipeline (routing, @Valid, JSON serialization, this class's
    // @RestControllerAdvice) runs for real.
    @Autowired
    private MockMvc mockMvc;

    // Auto-configured by @WebMvcTest specifically so tests can build JSON request bodies
    // the same way the real app would serialize/deserialize them.
    @Autowired
    private ObjectMapper objectMapper;

    // @MockitoBean replaces the REAL MessageService bean in this test's Spring context
    // with a Mockito mock - the controller is fully real and fully wired by Spring, but
    // everything below it is faked. (@MockBean was the equivalent annotation in older
    // Spring Boot; it's deprecated/removed here in favor of @MockitoBean.)
    @MockitoBean
    private MessageService service;

    @Test
    void getAll_returnsMessages() throws Exception {
        when(service.findAll()).thenReturn(List.of(new MessageResponse(1L, "hi", Instant.now())));

        // mockMvc.perform() actually runs the request through Spring MVC's dispatch
        // pipeline; jsonPath() lets you assert on a specific field inside the JSON
        // response body without manually parsing it.
        mockMvc.perform(get("/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].text").value("hi"));
    }

    @Test
    void getById_returns404WhenMissing() throws Exception {
        // This is the actual proof that MessageExceptionHandler works: the mock throws
        // the exception exactly as the real service would for a missing id, and the
        // assertion confirms Spring MVC's exception-handling machinery converts it to a
        // real 404 HTTP response, not that the Java exception "isInstanceOf" something -
        // this is genuinely testing the HTTP-level behavior.
        when(service.findById(1L)).thenThrow(new MessageNotFoundException(1L));

        mockMvc.perform(get("/messages/1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void create_returns201WithBody() throws Exception {
        when(service.create(any())).thenReturn(new MessageResponse(1L, "hi", Instant.now()));

        mockMvc.perform(post("/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        // objectMapper.writeValueAsString serializes the request DTO to
                        // real JSON text, exactly like an actual HTTP client would send it
                        .content(objectMapper.writeValueAsString(new CreateMessageRequest("hi"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.text").value("hi"));
    }

    @Test
    void create_returns400WhenTextBlank() throws Exception {
        // No when(...) stub needed here: the request never reaches the (mocked) service
        // at all. @Valid + @NotBlank on CreateMessageRequest.text rejects this request
        // during Spring MVC's own argument-binding step - proof that validation runs
        // BEFORE the controller method body, not something the controller has to check
        // itself.
        mockMvc.perform(post("/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateMessageRequest(""))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void delete_returns204() throws Exception {
        mockMvc.perform(delete("/messages/1"))
                .andExpect(status().isNoContent());
    }
}
