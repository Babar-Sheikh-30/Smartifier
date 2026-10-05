package com.bytesolutions.smartifier.models;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("users")
public record Account(@Id String id, String name, String email, String passwordHash,
                      Role role, Instant createdAt) {
    public enum Role { MEMBER, ADMIN }
}
