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

// A PURE UNIT TEST - no Spring context is started at all (contrast with
// MessageControllerTest's @WebMvcTest, which boots a slice of Spring). This is possible
// ONLY because MessageService uses constructor injection: @InjectMocks can simply call
// `new MessageService(mockRepository)` directly. Runs in milliseconds; no database, no web
// server, no classpath scanning.
//
// @ExtendWith(MockitoExtension.class) is what makes the @Mock/@InjectMocks annotations
// below actually do anything - without it they're inert.
@ExtendWith(MockitoExtension.class)
class MessageServiceTest {

    // A fake MessageRepository - no real Postgres/H2 involved. Its methods do nothing
    // until you tell them to via when(...).thenReturn(...) in each test.
    @Mock
    private MessageRepository repository;

    // Mockito constructs the real MessageService, but supplies the mock above wherever
    // MessageService's constructor asks for a MessageRepository - hence the class needing
    // exactly one constructor for @InjectMocks to unambiguously use.
    @InjectMocks
    private MessageService service;

    @Test
    void findAll_mapsEntitiesToResponses() {
        // "when the mock's findAll() is called, return this fixed list" - the mock never
        // touches a real table; this line fully controls what the repository "contains"
        // for the duration of this one test.
        when(repository.findAll()).thenReturn(List.of(new Message("hello")));

        List<MessageResponse> result = service.findAll();

        // AssertJ's fluent assertions (assertThat(...).hasSize(...)) read close to plain
        // English and chain naturally - the standard modern replacement for JUnit's older
        // assertEquals(expected, actual) style.
        assertThat(result).hasSize(1);
        assertThat(result.get(0).text()).isEqualTo("hello");
    }

    @Test
    void findById_throwsWhenMissing() {
        // Optional.empty() simulates "no row with this id" without a real database ever
        // being asked the question.
        when(repository.findById(42L)).thenReturn(Optional.empty());

        // assertThatThrownBy captures the exception a lambda throws and lets you assert on
        // it, instead of a try/catch/fail block or JUnit's older assertThrows syntax.
        assertThatThrownBy(() -> service.findById(42L))
                .isInstanceOf(MessageNotFoundException.class);
    }

    @Test
    void create_savesAndReturnsResponse() {
        // any(Message.class) matches ANY Message argument - appropriate here because this
        // test isn't verifying WHAT gets passed to save(), only that save()'s return value
        // flows correctly into the service's response.
        when(repository.save(any(Message.class))).thenReturn(new Message("new message"));

        MessageResponse response = service.create(new CreateMessageRequest("new message"));

        assertThat(response.text()).isEqualTo("new message");
        // verify(...) checks an INTERACTION happened, as opposed to when(...) which
        // defines a mock's behavior - here, confirming save() was actually called (not
        // just that the response looked right, which wouldn't catch a bug where create()
        // forgot to persist anything at all).
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

        // No exception thrown is one half of "worked correctly" - this verify() call
        // confirms the OTHER half: that deleteById() was actually invoked, not just that
        // the existsById() check passed.
        verify(repository).deleteById(1L);
    }
}
