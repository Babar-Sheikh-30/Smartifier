package com.bytesolutions.smartifier.content.models;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.annotation.TypeAlias;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

@TypeAlias("com.bytesolutions.smartifier.models.ContentStack")
@Document("content_stacks")
// Default stacks have no owner; source fields are null unless a personal stack was copied.
// publishedVersion is null until a version is published. revision is MongoDB's concurrency
// counter, separate from the content version numbers kept in versions.
public record ContentStack(@Id String id, String ownerId, boolean defaultStack,
        String sourceStackId, Integer sourceVersion, Integer publishedVersion,
        List<Revision> versions, @Version Long revision, Boolean archived) {
    public ContentStack {
        versions = List.copyOf(versions);
        archived = Boolean.TRUE.equals(archived);
    }

    // Keep existing member callers and documents compatible with active stacks.
    public ContentStack(String id, String ownerId, boolean defaultStack, String sourceStackId,
            Integer sourceVersion, Integer publishedVersion, List<Revision> versions, Long revision) {
        this(id, ownerId, defaultStack, sourceStackId, sourceVersion, publishedVersion, versions, revision, false);
    }

    public enum Type { QUOTE, FACT, JOKE, VOCABULARY }
    public enum Status { DRAFT, PENDING_REVIEW, APPROVED, REJECTED }

    // Answers and attribution are optional: facts do not need a punchline or an author.
    public record Item(@NotNull Type type, @NotBlank @Size(max = 1000) String body,
            @Size(max = 1000) String answer, @Size(max = 300) String attribution,
            String id, @Size(max = 100) String title, @Size(max = 500) String description) {
        public Item(Type type, String body, String answer, String attribution) {
            this(type, body, answer, attribution, null, null, null);
        }
    }

    // New drafts have no submission timestamp or review note until those steps occur.
    public record Revision(int number, String name, String description, List<Item> items,
            Status status, Instant createdAt, Instant submittedAt, String reviewNote) {
        public Revision { items = List.copyOf(items); }

        public Revision withReview(Status newStatus, Instant submissionTime, String note) {
            return new Revision(number, name, description, items, newStatus, createdAt, submissionTime, note);
        }
    }

    public record Edit(@NotBlank @Size(max = 100) String name,
            @NotBlank(message = "Description is required.") @Size(max = 500) String description,
            @NotNull @Size(min = 1, max = 50) List<@NotNull @Valid Item> items,
            @PositiveOrZero long expectedRevision) {}

    // Saves append versions in order, so the last entry is the current working version.
    public Revision latest() { return versions.getLast(); }

    public ContentStack withVersions(List<Revision> updatedVersions) {
        return new ContentStack(id, ownerId, defaultStack, sourceStackId, sourceVersion,
                publishedVersion, updatedVersions, revision, archived);
    }

    // Return the position, not the content version number. The caller supplies its own error response.
    public int versionIndex(int number) {
        int index = -1;
        for (int i = 0; i < versions.size(); i++) {
            if (versions.get(i).number() == number) index = i;
        }
        return index;
    }
}
