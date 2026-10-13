package com.bytesolutions.smartifier.models;

import java.time.Instant;
import java.util.Set;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("users")
public record Account(@Id String id, String name, String email, String passwordHash,
                      Role role, @CreatedDate Instant createdAt, String phoneNumber, Set<String> subscriptions) {
    public Account {
        subscriptions = subscriptions == null ? Set.of() : Set.copyOf(subscriptions);
    }
    // Admins do not need a phone number. This constructor also supports legacy members
    // without one; new member registration requires a valid phone number.
    public Account(String id, String name, String email, String passwordHash, Role role, Instant createdAt) {
        this(id, name, email, passwordHash, role, createdAt, null, Set.of());
    }
    public enum Role { MEMBER, ADMIN }
}
