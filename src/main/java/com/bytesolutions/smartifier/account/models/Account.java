package com.bytesolutions.smartifier.account.models;

import java.time.Instant;
import java.util.Set;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.annotation.TypeAlias;

@TypeAlias("com.bytesolutions.smartifier.models.Account")
@Document("users")
public record Account(@Id String id, String name, String email, String passwordHash,
                      Role role, @CreatedDate Instant createdAt, String phoneNumber, Set<String> subscriptions,
                      Boolean premium) {
    public Account {
        // Older accounts may lack these fields: start with no subscriptions and no paid access.
        // Copy the set so callers cannot change an account's subscriptions in memory.
        subscriptions = subscriptions == null ? Set.of() : Set.copyOf(subscriptions);
        premium = Boolean.TRUE.equals(premium);
    }
    public Account(String id, String name, String email, String passwordHash, Role role, Instant createdAt,
                   String phoneNumber, Set<String> subscriptions) {
        // New accounts stay on the free tier until a verified payment activates premium.
        this(id, name, email, passwordHash, role, createdAt, phoneNumber, subscriptions, false);
    }
    // Admins do not need a phone number. This constructor also supports legacy members
    // without one; new member registration requires a valid phone number.
    public Account(String id, String name, String email, String passwordHash, Role role, Instant createdAt) {
        this(id, name, email, passwordHash, role, createdAt, null, Set.of());
    }
    public enum Role { MEMBER, ADMIN }
}
