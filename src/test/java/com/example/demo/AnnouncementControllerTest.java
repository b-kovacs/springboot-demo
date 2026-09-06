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

// A slice test: @WebMvcTest boots only the web layer (this controller plus Spring MVC's
// routing/JSON/validation/exception-handling infrastructure), not the JPA/database layer.
// The middle ground between AnnouncementServiceTest's zero-Spring unit tests and a full
// @SpringBootTest that would boot everything, database included.
//
// A note on the two imports above that look unusual: @WebMvcTest lives at
// org.springframework.boot.webmvc.test.autoconfigure, and ObjectMapper at
// tools.jackson.databind, in this Spring Boot 4.1.1 specifically - both real, current
// locations in this version, just newer than what most tutorials/Stack Overflow answers
// show, which were written against the long-standing classic paths
// (org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest and
// com.fasterxml.jackson.databind.ObjectMapper). Confirmed by inspecting the actual
// published jars on Maven Central rather than guessing.
@WebMvcTest(AnnouncementController.class)
class AnnouncementControllerTest {

    // Simulates HTTP requests against the controller without starting a real server
    // socket, while still running the full Spring MVC dispatch pipeline for real.
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // Replaces the real AnnouncementService bean in this test's Spring context with a
    // Mockito mock - the controller is fully real and fully wired, everything below it
    // is faked. (@MockitoBean is the current replacement for the deprecated @MockBean.)
    @MockitoBean
    private AnnouncementService service;

    @Test
    void getAll_returnsAnnouncements() throws Exception {
        when(service.findAll()).thenReturn(List.of(new AnnouncementResponse(1L, "office closed Monday", Instant.now())));

        mockMvc.perform(get("/announcements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].text").value("office closed Monday"));
    }

    @Test
    void getById_returns404WhenMissing() throws Exception {
        // This is the actual proof AnnouncementExceptionHandler works: the mock throws
        // exactly what the real service would for a missing id, and the assertion
        // confirms Spring MVC's exception handling turns it into a real 404 response.
        when(service.findById(1L)).thenThrow(new AnnouncementNotFoundException(1L));

        mockMvc.perform(get("/announcements/1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void create_returns201WithBody() throws Exception {
        when(service.create(any())).thenReturn(new AnnouncementResponse(1L, "new deploy process starts Monday", Instant.now()));

        mockMvc.perform(post("/announcements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateAnnouncementRequest("new deploy process starts Monday"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.text").value("new deploy process starts Monday"));
    }

    @Test
    void create_returns400WhenTextBlank() throws Exception {
        // No stub needed: the request never reaches the (mocked) service. @Valid +
        // @NotBlank on CreateAnnouncementRequest.text rejects it during Spring MVC's own
        // argument binding, before the controller method body runs at all.
        mockMvc.perform(post("/announcements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateAnnouncementRequest(""))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void delete_returns204() throws Exception {
        mockMvc.perform(delete("/announcements/1"))
                .andExpect(status().isNoContent());
    }
}
