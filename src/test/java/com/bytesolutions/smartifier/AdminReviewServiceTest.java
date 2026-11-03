package com.bytesolutions.smartifier;

import java.time.Instant;
import java.util.*;
import com.bytesolutions.smartifier.models.Account;
import com.bytesolutions.smartifier.models.ContentStack;
import com.bytesolutions.smartifier.models.ContentStack.*;
import com.bytesolutions.smartifier.repositories.AccountRepository;
import com.bytesolutions.smartifier.repositories.ContentStackRepository;
import com.bytesolutions.smartifier.services.AdminReviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminReviewServiceTest {
    private final ContentStackRepository stacks = mock(ContentStackRepository.class);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final AdminReviewService service = new AdminReviewService(stacks, accounts);
    private ContentStack stack;
    private final List<Item> items = List.of(new Item(Type.FACT, "Submitted fact", "", ""));

    @BeforeEach void setup() {
        stack = new ContentStack("personal", "owner", false, "source", 1, null, List.of(
                new Revision(1, "Submitted", "", items, Status.PENDING_REVIEW, Instant.EPOCH, Instant.EPOCH, null),
                new Revision(2, "New draft", "", items, Status.DRAFT, Instant.EPOCH, null, null)), 3L);
        when(stacks.findById("personal")).thenReturn(Optional.of(stack));
        owner(true);
    }
    private void owner(boolean premium) {
        when(accounts.findById("owner")).thenReturn(Optional.of(new Account("owner", "Member", "member@example.test", "hash",
                Account.Role.MEMBER, Instant.EPOCH, null, Set.of(), premium)));
    }

    @Test void queueContainsOnlyPendingSnapshotsFromPremiumMembers() {
        when(stacks.findPendingReviews()).thenReturn(List.of(stack));
        var queue = service.pending();
        assertEquals(1, queue.size());
        assertEquals(1, queue.getFirst().version().number());
        assertEquals("Member", queue.getFirst().memberName());
        assertEquals(3, queue.getFirst().revision());
        owner(false);
        assertTrue(service.pending().isEmpty());
    }

    @Test void approvalPreservesNewerDraftPrivacyContentAndSource() {
        service.decide("personal", 1, 3, true, " Looks good. ");
        var saved = ArgumentCaptor.forClass(ContentStack.class);
        verify(stacks).save(saved.capture());
        var result = saved.getValue();
        assertEquals(Status.APPROVED, result.versions().getFirst().status());
        assertEquals("Looks good.", result.versions().getFirst().reviewNote());
        assertEquals(items, result.versions().getFirst().items());
        assertEquals(Instant.EPOCH, result.versions().getFirst().submittedAt());
        assertEquals(stack.latest(), result.latest());
        assertEquals("owner", result.ownerId());
        assertEquals("source", result.sourceStackId());
        assertFalse(result.defaultStack());
        assertNull(result.publishedVersion());
    }

    @Test void denialStoresFeedbackWithoutCreatingAnotherContentVersion() {
        service.decide("personal", 1, 3, false, "Please verify the source.");
        var saved = ArgumentCaptor.forClass(ContentStack.class);
        verify(stacks).save(saved.capture());
        assertEquals(Status.REJECTED, saved.getValue().versions().getFirst().status());
        assertEquals("Please verify the source.", saved.getValue().versions().getFirst().reviewNote());
        assertEquals(2, saved.getValue().versions().size());
    }

    @Test void invalidTargetsAndStaleOrAlreadyReviewedVersionsCannotBeChanged() {
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> service.decide("personal", 1, 2, true, "")).getStatusCode().value());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> service.decide("personal", 2, 3, true, "")).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.decide("personal", 9, 3, true, "")).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.decide("missing", 1, 3, true, "")).getStatusCode().value());
        owner(false);
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> service.decide("personal", 1, 3, true, "")).getStatusCode().value());
        verify(stacks, never()).save(any());
    }

    @Test void concurrentAdminDecisionsReturnConflict() {
        when(stacks.save(any(ContentStack.class))).thenThrow(new OptimisticLockingFailureException("race"));
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> service.decide("personal", 1, 3, true, "")).getStatusCode().value());
    }

    @Test void denialRequiresAReasonEvenWhenBypassingTheFrontend() {
        for (String note : new String[] {null, "", "   "}) {
            assertEquals(400, assertThrows(ResponseStatusException.class,
                    () -> service.decide("personal", 1, 3, false, note)).getStatusCode().value());
        }
        verify(stacks, never()).save(any());
    }
}
