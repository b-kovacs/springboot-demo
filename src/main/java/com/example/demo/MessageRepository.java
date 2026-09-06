package com.example.demo;

import org.springframework.data.jpa.repository.JpaRepository;

// Spring Data JPA generates a working implementation of this interface AT RUNTIME - there
// is no MessageRepositoryImpl class anywhere in this codebase, and there doesn't need to
// be. Extending JpaRepository<Message, Long> (entity type, primary-key type) hands you
// findAll(), findById(), save(), deleteById(), existsById(), count(), and more, for free.
//
// This interface is also where you'd add derived query methods just by naming them
// correctly, e.g. "List<Message> findByTextContaining(String fragment);" - Spring parses
// the method name itself and generates the query, no SQL/JPQL written by hand. No such
// methods are needed here yet (findAll()/findById()/save()/existsById()/deleteById() from
// JpaRepository already cover everything MessageService needs).
public interface MessageRepository extends JpaRepository<Message, Long> {
}
