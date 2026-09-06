package com.example.demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageServiceTest {

    @Mock
    private MessageRepository repository;

    @InjectMocks
    private MessageService service;

    @Test
    void findAll_mapsEntitiesToResponses() {
        when(repository.findAll()).thenReturn(List.of(new Message("hello")));

        List<MessageResponse> result = service.findAll();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).text()).isEqualTo("hello");
    }

    @Test
    void findById_throwsWhenMissing() {
        when(repository.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(42L))
                .isInstanceOf(MessageNotFoundException.class);
    }

    @Test
    void create_savesAndReturnsResponse() {
        when(repository.save(any(Message.class))).thenReturn(new Message("new message"));

        MessageResponse response = service.create(new CreateMessageRequest("new message"));

        assertThat(response.text()).isEqualTo("new message");
        verify(repository).save(any(Message.class));
    }

    @Test
    void delete_throwsWhenMissing() {
        when(repository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(99L))
                .isInstanceOf(MessageNotFoundException.class);
    }

    @Test
    void delete_removesWhenPresent() {
        when(repository.existsById(1L)).thenReturn(true);

        service.delete(1L);

        verify(repository).deleteById(1L);
    }
}
