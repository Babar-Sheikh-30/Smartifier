package com.bytesolutions.smartifier.models;

import java.time.Instant;
import java.util.Set;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("users")
public record Account(@Id String id, String name, String email, String passwordHash,
                      Role role, Instant createdAt, String phoneNumber, Set<String> subscriptions) {
    public Account {
        subscriptions = subscriptions == null ? Set.of() : Set.copyOf(subscriptions);
    }
    public Account(String id, String name, String email, String passwordHash, Role role, Instant createdAt) {
        this(id, name, email, passwordHash, role, createdAt, null, Set.of());
    }
    public enum Role { MEMBER, ADMIN }
}
