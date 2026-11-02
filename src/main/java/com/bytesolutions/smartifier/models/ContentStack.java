package com.bytesolutions.smartifier.models;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

@Document("content_stacks")
public record ContentStack(@Id String id, String ownerId, boolean defaultStack,
        String sourceStackId, Integer sourceVersion, Integer publishedVersion,
        List<Revision> versions, @Version Long revision) {
    public ContentStack { versions = List.copyOf(versions); }

    public enum Type { QUOTE, FACT, JOKE, VOCABULARY }
    public enum Status { DRAFT, PENDING_REVIEW, APPROVED, REJECTED }

    public record Item(@NotNull Type type, @NotBlank @Size(max = 1000) String body,
            @Size(max = 1000) String answer, @Size(max = 300) String attribution) {}

    public record Revision(int number, String name, String description, List<Item> items,
            Status status, Instant createdAt, Instant submittedAt, String reviewNote) {
        public Revision { items = List.copyOf(items); }
    }

    public record Edit(@NotBlank @Size(max = 100) String name,
            @Size(max = 500) String description,
            @NotNull @Size(min = 1, max = 50) List<@NotNull @Valid Item> items,
            @PositiveOrZero long expectedRevision) {}

    public Revision latest() { return versions.getLast(); }
}
