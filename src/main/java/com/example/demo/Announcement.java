package com.example.demo;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;

// A short announcement posted internally, like "office closed Monday" or "new deploy
// process starts next sprint". The use case: a small internal service any team can post
// to and any team can read from, instead of announcements getting lost in chat.
//
// @Entity tells Hibernate this class maps to a database table. By default the table name
// is the class name lowercased ("announcement"), and each field maps to a column of the
// same name.
//
// This class is never returned directly from the REST API. See AnnouncementController:
// an entity is Hibernate's internal representation, and exposing it straight over HTTP
// couples your wire format to your database schema. AnnouncementResponse is the plain,
// JPA-unaware object that actually crosses the HTTP boundary.
@Entity
public class Announcement {
    // @Id marks the primary key. @GeneratedValue(strategy = IDENTITY) lets the database
    // itself generate the id (a Postgres auto-increment column), one round trip per insert.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String text;
    private Instant createdAt;

    // JPA requires a no-arg constructor so Hibernate can create the object via reflection
    // before filling in its fields from a query result. It's not meant to be called from
    // application code, hence "protected" rather than "public".
    protected Announcement() {
    }

    // The constructor application code actually uses. Setting createdAt here means the
    // exact posting time is known immediately, without a round trip to Postgres.
    public Announcement(String text) {
        this.text = text;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getText() {
        return text;
    }

    // No setId()/setCreatedAt() on purpose: identity and posting time are set once and
    // never change. Only the text itself is mutable.
    public void setText(String text) {
        this.text = text;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
