package com.example.demo;

import org.springframework.data.jpa.repository.JpaRepository;

// Spring Data JPA generates a working implementation of this interface at runtime. There
// is no AnnouncementRepositoryImpl class anywhere in this codebase, and there doesn't
// need to be. Extending JpaRepository<Announcement, Long> (entity type, primary key type)
// gives you findAll(), findById(), save(), deleteById(), existsById(), and more, for free.
//
// This is also where you'd add a query like "findByCreatedAtAfter(Instant since)" just by
// naming the method correctly, if a future feature needed "announcements from the last
// week." Not needed yet, so it's not here.
public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {
}
