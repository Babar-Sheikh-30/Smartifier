package com.bytesolutions.smartifier.content.unit;

import com.bytesolutions.smartifier.account.models.Account;
import com.bytesolutions.smartifier.account.repositories.AccountRepository;
import com.bytesolutions.smartifier.account.services.AccountService;
import com.bytesolutions.smartifier.content.models.ContentStack;
import com.bytesolutions.smartifier.content.models.ContentStack.*;
import com.bytesolutions.smartifier.content.repositories.ContentStackRepository;
import com.bytesolutions.smartifier.content.services.*;
import com.bytesolutions.smartifier.sms.services.SmsContentService;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SharedContributionTest {
    final ContentStackRepository stacks = mock(ContentStackRepository.class);
    final AccountService accounts = mock(AccountService.class);
    final AccountRepository accountRepository = mock(AccountRepository.class);
    final ContentStackService member = new ContentStackService(accounts, stacks);
    final AdminContentService admin = new AdminContentService(stacks);
    final AdminReviewService reviews = new AdminReviewService(stacks, accountRepository, admin);
    final Item live = new Item(Type.FACT, "Published fact", "", "", "live", null, null);
    final Item unrelated = new Item(Type.FACT, "Unrelated draft", "", "", "draft", null, null);
    final Item suggestion = new Item(Type.FACT, "New suggestion", "Context", "Source");
    Account owner;
    ContentStack stored;

    @BeforeEach void setup() {
        owner = account(true);
        when(accounts.current("member@test")).thenAnswer(invocation -> owner);
        when(accountRepository.findById("owner")).thenAnswer(invocation -> Optional.of(owner));
        when(accountRepository.findByEmail("admin@test")).thenReturn(Optional.of(new Account("admin", "Admin", "admin@test", "hash", Account.Role.ADMIN, Instant.EPOCH, null, Set.of(), false)));
        stored = new ContentStack("shared", null, true, null, null, 1, List.of(
            new Revision(1, "Shared", "Public", List.of(live), Status.APPROVED, Instant.EPOCH, null, null),
            new Revision(2, "Shared", "Public", List.of(live, unrelated), Status.DRAFT, Instant.EPOCH, null, null)), 4L);
        when(stacks.findById("shared")).thenAnswer(invocation -> Optional.of(stored));
        when(stacks.save(any(ContentStack.class))).thenAnswer(invocation -> {
            ContentStack input = invocation.getArgument(0);
            stored = new ContentStack(input.id(), input.ownerId(), input.defaultStack(), input.sourceStackId(), input.sourceVersion(),
                input.publishedVersion(), input.versions(), input.revision() + 1, input.archived(), input.contributions());
            return stored;
        });
    }
    Account account(boolean premium) { return new Account("owner", "Member", "member@test", "hash", Account.Role.MEMBER, Instant.EPOCH, "+14165551234", Set.of("shared"), premium); }
    Contribution submit() { return member.contribute("member@test", "shared", suggestion).contribution(); }
    void status(int expected, Runnable work) { assertEquals(expected, assertThrows(ResponseStatusException.class, work::run).getStatusCode().value()); }

    @Test void submissionIsPremiumOnlyAndRetainsSnapshotWithoutChangingVersionsOrPublication() {
        owner = account(false);
        status(403, this::submit);
        verify(stacks, never()).save(any());
        owner = account(true);
        var before = stored;
        var result = submit();
        assertEquals("owner", result.contributorId());
        assertEquals(Status.PENDING_REVIEW, result.status());
        assertNotNull(result.submittedAt());
        assertEquals("New suggestion", result.item().body());
        assertEquals(before.versions(), stored.versions());
        assertEquals(before.publishedVersion(), stored.publishedVersion());
        status(409, this::submit);
        status(409, () -> member.contribute("member@test", "shared", live));
    }

    @Test void missingArchivedPrivateAndUnpublishedDestinationsRejectSubmissions() {
        status(404, () -> member.contribute("member@test", "missing", suggestion));
        var original = stored;
        stored = new ContentStack("shared", null, true, null, null, 1, original.versions(), 4L, true);
        status(404, this::submit);
        stored = new ContentStack("shared", "owner", false, null, null, null, original.versions(), 4L);
        status(404, this::submit);
        stored = new ContentStack("shared", null, true, null, null, null, original.versions(), 4L);
        status(404, this::submit);
        verify(stacks, never()).save(any());
    }

    @Test void approvalAddsOnlyADraftAndSelectedPublicationNeverIncludesUnrelatedDrafts() {
        var contribution = submit();
        var sms = new SmsContentService(stacks);
        assertEquals(List.of(live), sms.eligibleItems(owner));
        var publication = stored.publishedVersion();
        reviews.decideContribution("shared", contribution.id(), stored.revision(), true, "Accepted", "admin@test");
        assertEquals(publication, stored.publishedVersion());
        assertEquals(Status.DRAFT, stored.latest().status());
        assertTrue(stored.latest().items().contains(unrelated));
        assertTrue(stored.latest().items().contains(contribution.item()));
        assertEquals(List.of(live), sms.eligibleItems(owner));
        assertEquals(Status.APPROVED, stored.contributions().getFirst().status());
        assertEquals("admin", stored.contributions().getFirst().reviewerId());
        admin.publish("shared", contribution.item().id(), stored.revision());
        assertEquals(List.of(live, contribution.item()), sms.eligibleItems(owner));
        assertFalse(sms.eligibleItems(owner).contains(unrelated));
        assertEquals(Status.APPROVED, stored.contributions().getFirst().status());
        admin.metadata("shared", stored.revision(), "Updated", "Description");
        assertEquals(1, stored.contributions().size());
        admin.remove("shared", stored.revision());
        assertEquals(1, stored.contributions().size());
        assertTrue(sms.eligibleItems(owner).isEmpty());
    }

    @Test void denialRetainsFeedbackHistoryAndDoesNotChangeContent() {
        var contribution = submit();
        var versions = stored.versions();
        status(400, () -> reviews.decideContribution("shared", contribution.id(), stored.revision(), false, " ", "admin@test"));
        reviews.decideContribution("shared", contribution.id(), stored.revision(), false, "  Verify source  ", "admin@test");
        assertEquals(versions, stored.versions());
        assertEquals(Status.REJECTED, stored.contributions().getFirst().status());
        assertEquals("Verify source", stored.contributions().getFirst().reviewNote());
        assertNotNull(stored.contributions().getFirst().reviewedAt());
        status(409, () -> reviews.decideContribution("shared", contribution.id(), stored.revision(), true, "", "admin@test"));
    }

    @Test void approvalRejectsStaleMissingArchivedOrAlreadyReviewedAndConcurrentChanges() {
        var contribution = submit();
        status(403, () -> reviews.decideContribution("shared", contribution.id(), stored.revision(), true, "", "member@test"));
        status(409, () -> reviews.decideContribution("shared", contribution.id(), 0, true, "", "admin@test"));
        status(404, () -> reviews.decideContribution("shared", "missing", stored.revision(), true, "", "admin@test"));
        admin.remove("shared", stored.revision());
        status(409, () -> reviews.decideContribution("shared", contribution.id(), stored.revision(), true, "", "admin@test"));
        // Denial of an archived submission remains available to clear the inbox.
        reviews.decideContribution("shared", contribution.id(), stored.revision(), false, "Destination archived", "admin@test");
        assertTrue(stored.archived());
    }

    @Test void racingSubmissionsAndReviewsFailWithoutPartialDecisions() {
        var contribution = submit();
        var before = stored;
        when(stacks.save(any(ContentStack.class))).thenThrow(new OptimisticLockingFailureException("race"));
        status(409, () -> reviews.decideContribution("shared", contribution.id(), stored.revision(), true, "", "admin@test"));
        assertEquals(before, stored);
        status(409, () -> member.contribute("member@test", "shared", new Item(Type.FACT, "Another fact", "", "")));
        assertEquals(before, stored);
    }

    @Test void historyAndQueuePreserveOwnershipEvenAfterPremiumExpires() {
        var contribution = submit();
        var other = new Contribution("other", "another-member", suggestion, Instant.EPOCH, Status.PENDING_REVIEW, null, null, null);
        stored = stored.withContributions(List.of(contribution, other));
        owner = account(false);
        when(stacks.findContributionsByContributor("owner")).thenReturn(List.of(stored));
        var history = member.contributions("member@test");
        assertEquals(1, history.size());
        assertEquals(contribution, history.getFirst().contribution());
        when(stacks.findPendingContributions()).thenReturn(List.of(stored));
        assertEquals(2, reviews.pending().size());
        assertTrue(reviews.pending().stream().anyMatch(e -> contribution.id().equals(e.contributionId())));
    }
}
