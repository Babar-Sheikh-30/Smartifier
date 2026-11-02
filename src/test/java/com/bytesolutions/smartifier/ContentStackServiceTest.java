package com.bytesolutions.smartifier;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import com.bytesolutions.smartifier.models.Account;
import com.bytesolutions.smartifier.models.ContentStack;
import com.bytesolutions.smartifier.models.ContentStack.*;
import com.bytesolutions.smartifier.repositories.ContentStackRepository;
import com.bytesolutions.smartifier.services.AccountService;
import com.bytesolutions.smartifier.services.ContentStackService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentStackServiceTest {
    private final AccountService accounts = mock(AccountService.class);
    private final ContentStackRepository repository = mock(ContentStackRepository.class);
    private final ContentStackService service = new ContentStackService(accounts, repository);
    private final String email = "member@example.com";
    private final List<Item> mixed = List.of(
            new Item(Type.FACT, "A fact", "", ""),
            new Item(Type.QUOTE, "A quote", "", "An author"),
            new Item(Type.JOKE, "A setup", "A punchline", ""),
            new Item(Type.VOCABULARY, "Serendipity", "A fortunate discovery", ""));

    private void member(boolean premium) {
        when(accounts.current(email)).thenReturn(new Account("owner-1", "Member", email, "hash",
                Account.Role.MEMBER, Instant.EPOCH, "+14165551234", Set.of(), premium));
    }

    private ContentStack stack(boolean isDefault, Status status, Long revision) {
        return new ContentStack("stack-1", isDefault ? null : "owner-1", isDefault, null, null,
                isDefault ? 1 : null,
                List.of(new Revision(1, "Mixed stack", "A description", mixed, status, Instant.EPOCH, null, null)), revision);
    }

    @Test
    void freeMembersCannotReadCreateCopyEditOrSubmitPersonalStacks() {
        member(false);
        var edit = new Edit("New stack", "", mixed, 0);
        for (Runnable operation : List.<Runnable>of(
                () -> service.personal(email), () -> service.create(email, edit),
                () -> service.copy(email, "stack-1"), () -> service.save(email, "stack-1", edit),
                () -> service.submit(email, "stack-1", 0))) {
            assertEquals(403, assertThrows(ResponseStatusException.class, operation::run).getStatusCode().value());
        }
        verifyNoInteractions(repository);
    }

    @Test
    void defaultsExposeOnlyPublishedSummaryAndKeepContentPrivate() {
        var published = stack(true, Status.APPROVED, 0L);
        when(repository.findByDefaultStackTrue()).thenReturn(List.of(published,
                new ContentStack("unpublished", null, true, null, null, null, published.versions(), 0L)));
        assertEquals(List.of(new ContentStackService.Summary("stack-1", "Mixed stack", "A description", 4)), service.defaults());
        verifyNoInteractions(accounts);
    }

    @Test
    void copyingDefaultCreatesIndependentOwnedDraftWithMixedContentAndSourceVersion() {
        member(true);
        var original = stack(true, Status.APPROVED, 0L);
        when(repository.findById("stack-1")).thenReturn(Optional.of(original));
        when(repository.insert(any(ContentStack.class))).thenAnswer(call -> call.getArgument(0));
        var copy = service.copy(email, "stack-1");
        assertNull(copy.id());
        assertEquals("owner-1", copy.ownerId());
        assertFalse(copy.defaultStack());
        assertEquals("stack-1", copy.sourceStackId());
        assertEquals(1, copy.sourceVersion());
        assertNull(copy.publishedVersion());
        assertEquals(Status.DRAFT, copy.latest().status());
        assertEquals(mixed, copy.latest().items());
        assertEquals(Status.APPROVED, original.latest().status());
        verify(repository, never()).save(any());
    }

    @Test
    void editingPreservesSubmittedSnapshotAndAppendsNewDraft() {
        member(true);
        var original = stack(false, Status.PENDING_REVIEW, 3L);
        when(repository.findByIdAndOwnerId("stack-1", "owner-1")).thenReturn(Optional.of(original));
        when(repository.save(any(ContentStack.class))).thenAnswer(call -> call.getArgument(0));
        var edited = service.save(email, "stack-1", new Edit("Updated", "", mixed, 3));
        assertEquals(2, edited.versions().size());
        assertEquals(original.latest(), edited.versions().getFirst());
        assertEquals(Status.PENDING_REVIEW, original.latest().status());
        assertEquals(Status.DRAFT, edited.latest().status());
        assertEquals(2, edited.latest().number());
        assertEquals("Updated", edited.latest().name());
    }

    @Test
    void submissionFreezesSavedContentWithoutPublishingAndCannotBeRepeated() {
        member(true);
        var draft = stack(false, Status.DRAFT, 2L);
        when(repository.findByIdAndOwnerId("stack-1", "owner-1")).thenReturn(Optional.of(draft));
        when(repository.save(any(ContentStack.class))).thenAnswer(call -> call.getArgument(0));
        var submitted = service.submit(email, "stack-1", 2);
        assertEquals(Status.PENDING_REVIEW, submitted.latest().status());
        assertEquals(mixed, submitted.latest().items());
        assertNotNull(submitted.latest().submittedAt());
        assertNull(submitted.publishedVersion());
        when(repository.findByIdAndOwnerId("stack-1", "owner-1")).thenReturn(Optional.of(submitted));
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> service.submit(email, "stack-1", 2)).getStatusCode().value());
    }

    @Test
    void anotherMembersStackAndDefaultsCannotBeEditedThroughPersonalEndpoints() {
        member(true);
        when(repository.findByIdAndOwnerId("other-stack", "owner-1")).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> service.save(email, "other-stack", new Edit("Hijack", "", mixed, 0))).getStatusCode().value());
        when(repository.findByIdAndOwnerId("stack-1", "owner-1"))
                .thenReturn(Optional.of(stack(true, Status.APPROVED, 0L)));
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> service.submit(email, "stack-1", 0)).getStatusCode().value());
        verify(repository, never()).save(any());
    }

    @Test
    void staleEditorAndConcurrentSavesReturnConflictInsteadOfOverwritingHistory() {
        member(true);
        when(repository.findByIdAndOwnerId("stack-1", "owner-1"))
                .thenReturn(Optional.of(stack(false, Status.DRAFT, 4L)));
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> service.save(email, "stack-1", new Edit("Old", "", mixed, 3))).getStatusCode().value());
        verify(repository, never()).save(any());
        when(repository.save(any(ContentStack.class))).thenThrow(new OptimisticLockingFailureException("concurrent update"));
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> service.save(email, "stack-1", new Edit("Current", "", mixed, 4))).getStatusCode().value());
    }

    @Test
    void versionLimitPreventsUnboundedMongoDocuments() {
        member(true);
        var full = new ContentStack("stack-1", "owner-1", false, null, null, null,
                java.util.stream.IntStream.rangeClosed(1, 40)
                        .mapToObj(number -> new Revision(number, "Name", "", mixed, Status.DRAFT, Instant.EPOCH, null, null))
                        .toList(), 40L);
        when(repository.findByIdAndOwnerId("stack-1", "owner-1")).thenReturn(Optional.of(full));
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> service.save(email, "stack-1", new Edit("One more", "", mixed, 40))).getStatusCode().value());
        verify(repository, never()).save(any());
    }
}
