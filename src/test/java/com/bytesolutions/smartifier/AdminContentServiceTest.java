package com.bytesolutions.smartifier;

import java.time.Instant;
import java.util.*;
import com.bytesolutions.smartifier.models.ContentStack;
import com.bytesolutions.smartifier.models.ContentStack.*;
import com.bytesolutions.smartifier.repositories.ContentStackRepository;
import com.bytesolutions.smartifier.services.AdminContentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminContentServiceTest {
    private final ContentStackRepository repository = mock(ContentStackRepository.class);
    private final AdminContentService service = new AdminContentService(repository);
    private ContentStack stored;
    private Item content(String id, String body) { return new Item(Type.FACT, body, "", "", id, "Title", "Description"); }

    @BeforeEach void setup() {
        stored = new ContentStack("shared", null, true, null, null, null,
                List.of(new Revision(1, "Stack", "Description", List.of(), Status.DRAFT, Instant.EPOCH, null, null)), 0L);
        when(repository.findById("shared")).thenAnswer(call -> Optional.of(stored));
        when(repository.save(any(ContentStack.class))).thenAnswer(call -> {
            ContentStack saved = call.getArgument(0);
            stored = new ContentStack(saved.id(), saved.ownerId(), saved.defaultStack(), saved.sourceStackId(),
                    saved.sourceVersion(), saved.publishedVersion(), saved.versions(), saved.revision() + 1, saved.archived());
            return stored;
        });
    }

    @Test void publishesOnlySelectedItemAndPreservesWorkingDraftAndHistory() {
        service.add("shared", 0, content(null, "First"));
        service.add("shared", 1, content(null, "Second"));
        String first = stored.latest().items().getFirst().id();
        String second = stored.latest().items().getLast().id();
        service.publish("shared", first, 2);
        var live = stored.versions().stream().filter(v -> v.number() == stored.publishedVersion()).findFirst().orElseThrow();
        assertEquals(List.of("First"), live.items().stream().map(Item::body).toList());
        assertEquals(2, stored.latest().items().size());
        assertEquals(Status.DRAFT, stored.latest().status());
        assertTrue(stored.defaultStack());
        service.edit("shared", first, 3, content(null, "Updated first"));
        service.publish("shared", second, 4);
        live = stored.versions().stream().filter(v -> v.number() == stored.publishedVersion()).findFirst().orElseThrow();
        assertEquals(List.of("First", "Second"), live.items().stream().map(Item::body).toList());
        assertEquals("Updated first", stored.latest().items().getFirst().body());
        service.removeItem("shared", first, 5);
        service.removeItem("shared", second, 6);
        assertNull(stored.publishedVersion());
        assertTrue(stored.latest().items().isEmpty());
        assertTrue(stored.versions().stream().anyMatch(v -> v.items().stream().anyMatch(i -> i.body().equals("First"))));
    }

    @Test void metadataRemainsDraftUntilExplicitlyPublished() {
        service.add("shared", 0, content(null, "Fact"));
        service.publish("shared", stored.latest().items().getFirst().id(), 1);
        int previous = stored.publishedVersion();
        service.metadata("shared", 2, "New name", "New description");
        assertEquals(previous, stored.publishedVersion());
        assertEquals("Stack", stored.versions().stream().filter(v -> v.number() == previous).findFirst().orElseThrow().name());
        service.publishMetadata("shared", 3);
        assertEquals("New name", stored.versions().stream().filter(v -> v.number() == stored.publishedVersion()).findFirst().orElseThrow().name());
    }

    @Test void legacyIdsAreStableAndPersistOnSaveWithoutWritingDuringRead() {
        stored = new ContentStack("shared", null, true, null, null, 1,
                List.of(new Revision(1, "Legacy", "", List.of(new Item(Type.FACT, "Legacy fact", "", "")),
                        Status.APPROVED, Instant.EPOCH, null, null)), 0L);
        String id = service.get("shared").latest().items().getFirst().id();
        assertEquals(id, service.get("shared").latest().items().getFirst().id());
        verify(repository, never()).save(any());
        service.edit("shared", id, 0, content(null, "Changed"));
        assertEquals(id, stored.latest().items().getFirst().id());
        assertEquals(id, stored.versions().getFirst().items().getFirst().id());
    }

    @Test void removalDoesNotPublishUnrelatedMetadataDrafts() {
        service.add("shared", 0, content(null, "First"));
        service.add("shared", 1, content(null, "Second"));
        String first = stored.latest().items().getFirst().id();
        String second = stored.latest().items().getLast().id();
        service.publish("shared", first, 2);
        service.publish("shared", second, 3);
        service.metadata("shared", 4, "Draft name", "Draft description");
        service.removeItem("shared", first, 5);
        var published = stored.versions().stream().filter(v -> v.number() == stored.publishedVersion()).findFirst().orElseThrow();
        assertEquals("Stack", published.name());
        assertEquals("Draft name", stored.latest().name());
        assertEquals(List.of(second), published.items().stream().map(Item::id).toList());
    }

    @Test void rejectsMissingPrivateArchivedAndStaleResources() {
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.get("missing")).getStatusCode().value());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> service.metadata("shared", 99, "Name", "")).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.removeItem("shared", "missing", 0)).getStatusCode().value());
        service.remove("shared", 0);
        assertTrue(stored.archived());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.get("shared")).getStatusCode().value());
        stored = new ContentStack("shared", "member", false, null, null, null, stored.versions(), 1L);
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.get("shared")).getStatusCode().value());
    }

    @Test void concurrentWritesAndVersionLimitsDoNotOverwriteData() {
        when(repository.save(any(ContentStack.class))).thenThrow(new OptimisticLockingFailureException("race"));
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> service.metadata("shared", 0, "Name", "")).getStatusCode().value());
        stored = new ContentStack("shared", null, true, null, null, null,
                java.util.stream.IntStream.rangeClosed(1, 40).mapToObj(n -> new Revision(n, "Name", "", List.<Item>of(), Status.DRAFT, Instant.EPOCH, null, null)).toList(), 0L);
        clearInvocations(repository);
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> service.add("shared", 0, content(null, "Fact"))).getStatusCode().value());
        verify(repository, never()).save(any());
        doAnswer(call -> call.getArgument(0)).when(repository).save(any(ContentStack.class));
        assertDoesNotThrow(() -> service.remove("shared", 0));
    }
}
