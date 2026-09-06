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

// A pure unit test: no Spring context at all, possible only because AnnouncementService
// uses constructor injection, so @InjectMocks can just call
// `new AnnouncementService(mockRepository)` directly. Runs in milliseconds.
@ExtendWith(MockitoExtension.class)
class AnnouncementServiceTest {

    // A fake AnnouncementRepository - no real database involved. Its methods do nothing
    // until told to via when(...).thenReturn(...) in each test.
    @Mock
    private AnnouncementRepository repository;

    @InjectMocks
    private AnnouncementService service;

    @Test
    void findAll_mapsEntitiesToResponses() {
        when(repository.findAll()).thenReturn(List.of(new Announcement("office closed Monday")));

        List<AnnouncementResponse> result = service.findAll();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).text()).isEqualTo("office closed Monday");
    }

    @Test
    void findById_throwsWhenMissing() {
        when(repository.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(42L))
                .isInstanceOf(AnnouncementNotFoundException.class);
    }

    @Test
    void create_savesAndReturnsResponse() {
        when(repository.save(any(Announcement.class))).thenReturn(new Announcement("new deploy process starts Monday"));

        AnnouncementResponse response = service.create(new CreateAnnouncementRequest("new deploy process starts Monday"));

        assertThat(response.text()).isEqualTo("new deploy process starts Monday");
        // verify checks an INTERACTION happened, unlike when(...) which defines mock
        // behavior - confirms save() was actually called, not just that the response
        // looked right, which wouldn't catch a bug where create() forgot to persist at all.
        verify(repository).save(any(Announcement.class));
    }

    @Test
    void delete_throwsWhenMissing() {
        when(repository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(99L))
                .isInstanceOf(AnnouncementNotFoundException.class);
    }

    @Test
    void delete_removesWhenPresent() {
        when(repository.existsById(1L)).thenReturn(true);

        service.delete(1L);

        verify(repository).deleteById(1L);
    }
}
