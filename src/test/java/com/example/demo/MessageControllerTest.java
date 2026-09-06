package com.example.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(MessageController.class)
class MessageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private MessageService service;

    @Test
    void getAll_returnsMessages() throws Exception {
        when(service.findAll()).thenReturn(List.of(new MessageResponse(1L, "hi", Instant.now())));

        mockMvc.perform(get("/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].text").value("hi"));
    }

    @Test
    void getById_returns404WhenMissing() throws Exception {
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
                        .content(objectMapper.writeValueAsString(new CreateMessageRequest("hi"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.text").value("hi"));
    }

    @Test
    void create_returns400WhenTextBlank() throws Exception {
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
