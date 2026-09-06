package com.example.demo;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;

// @Entity is a JPA annotation, not a Spring one - it tells Hibernate (the JPA provider
// Spring Data JPA uses under the hood) "this class maps to a database table." By default
// the table name is the class name lowercased ("message") and each field maps to a column
// of the same name, unless overridden with @Table/@Column.
//
// WHY this class is never returned directly from the REST API (see MessageController):
// exposing a JPA entity straight over HTTP couples your wire format to your database
// schema - renaming a column breaks API clients, and Hibernate's lazy-loading proxies can
// serialize in surprising/broken ways. MessageResponse (a plain record, JPA-unaware) is
// the DTO that actually crosses the HTTP boundary.
@Entity
public class Message {
    // @Id marks the primary key. @GeneratedValue(strategy = IDENTITY) delegates ID
    // generation to the database itself (Postgres SERIAL/IDENTITY column, auto-increment)
    // rather than Hibernate pre-allocating IDs - simplest strategy, one round-trip per
    // insert, fine for this scale.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String text;
    private Instant createdAt;

    // JPA REQUIRES a no-arg constructor (can be protected/private) so Hibernate can
    // instantiate the entity via reflection before populating its fields from a query
    // result. It's never meant to be called from application code directly - hence
    // "protected", not "public": visible to Hibernate and this package, not to callers
    // who should use the constructor below instead.
    protected Message() {
    }

    // The constructor application code actually uses. Setting createdAt here (not left
    // for the database to default) means the exact creation instant is known and testable
    // in Java without round-tripping through Postgres.
    public Message(String text) {
        this.text = text;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getText() {
        return text;
    }

    // Deliberately no setId()/setCreatedAt(): identity and creation time are set once,
    // at construction, and never change - only text is mutable, so only it gets a setter.
    public void setText(String text) {
        this.text = text;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
