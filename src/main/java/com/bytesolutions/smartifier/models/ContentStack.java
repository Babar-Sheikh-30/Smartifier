package com.bytesolutions.smartifier.models;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

@Document("content_stacks")
// Default stacks have no owner; source fields are null unless a personal stack was copied.
// publishedVersion is null until a version is published. revision is MongoDB's concurrency
// counter, separate from the content version numbers kept in versions.
public record ContentStack(@Id String id, String ownerId, boolean defaultStack,
        String sourceStackId, Integer sourceVersion, Integer publishedVersion,
        List<Revision> versions, @Version Long revision) {
    public ContentStack { versions = List.copyOf(versions); }

    public enum Type { QUOTE, FACT, JOKE, VOCABULARY }
    public enum Status { DRAFT, PENDING_REVIEW, APPROVED, REJECTED }

    // Answers and attribution are optional: facts do not need a punchline or an author.
    public record Item(@NotNull Type type, @NotBlank @Size(max = 1000) String body,
            @Size(max = 1000) String answer, @Size(max = 300) String attribution) {}

    // New drafts have no submission timestamp or review note until those steps occur.
    public record Revision(int number, String name, String description, List<Item> items,
            Status status, Instant createdAt, Instant submittedAt, String reviewNote) {
        public Revision { items = List.copyOf(items); }
    }

    public record Edit(@NotBlank @Size(max = 100) String name,
            @Size(max = 500) String description,
            @NotNull @Size(min = 1, max = 50) List<@NotNull @Valid Item> items,
            @PositiveOrZero long expectedRevision) {}

    // Saves append versions in order, so the last entry is the current working version.
    public Revision latest() { return versions.getLast(); }
}
