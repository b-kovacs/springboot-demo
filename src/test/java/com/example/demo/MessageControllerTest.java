package com.example.demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageControllerTest {

    @Mock
    private MessageService service;

    @InjectMocks
    private MessageController controller;

    @Test
    void all_delegatesToService() {
        MessageResponse response = new MessageResponse(1L, "hi", Instant.now());
        when(service.findAll()).thenReturn(List.of(response));

        assertThat(controller.all()).containsExactly(response);
    }

    @Test
    void byId_delegatesToService() {
        MessageResponse response = new MessageResponse(1L, "hi", Instant.now());
        when(service.findById(1L)).thenReturn(response);

        assertThat(controller.byId(1L)).isEqualTo(response);
    }

    @Test
    void byId_propagatesNotFound() {
        when(service.findById(99L)).thenThrow(new MessageNotFoundException(99L));

        assertThatThrownBy(() -> controller.byId(99L))
                .isInstanceOf(MessageNotFoundException.class);
    }

    @Test
    void create_delegatesToService() {
        CreateMessageRequest request = new CreateMessageRequest("hi");
        MessageResponse response = new MessageResponse(1L, "hi", Instant.now());
        when(service.create(request)).thenReturn(response);

        assertThat(controller.create(request)).isEqualTo(response);
    }

    @Test
    void delete_delegatesToService() {
        controller.delete(1L);

        verify(service).delete(1L);
    }
}
