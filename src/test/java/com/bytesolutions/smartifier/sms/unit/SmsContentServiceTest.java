package com.bytesolutions.smartifier.sms.unit;

import com.bytesolutions.smartifier.sms.services.SmsContentService;

import com.bytesolutions.smartifier.account.models.Account;
import com.bytesolutions.smartifier.content.models.ContentStack;
import com.bytesolutions.smartifier.content.models.ContentStack.*;
import com.bytesolutions.smartifier.content.repositories.ContentStackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SmsContentServiceTest {

    private ContentStackRepository stacks;
    private SmsContentService service;

    @BeforeEach
    void setUp() {
        stacks = mock(ContentStackRepository.class);
        service = new SmsContentService(stacks);
    }

    private Account member(boolean premium, Set<String> subscriptions) {
        return new Account(
                "member-1", "Test Member", "test@example.com",
                "hash", Account.Role.MEMBER, Instant.now(),
                "+14165551234", subscriptions, premium
        );
    }

    private ContentStack stack(
            String id, boolean defaultStack, String ownerId,
            Status status, boolean archived) {

        Item item = new Item(Type.FACT, "The Earth orbits the Sun.", null, null);

        Revision version = new Revision(
                1, "Facts", "Educational facts", List.of(item),
                status, Instant.now(), null, null
        );

        return new ContentStack(
                id, ownerId, defaultStack, null, null,
                1, List.of(version), 1L, archived
        );
    }

    @Test
    void returnsApprovedPublishedDefaultContent() {
        when(stacks.findById("facts")).thenReturn(Optional.of(
                stack("facts", true, null, Status.APPROVED, false)));

        var items = service.eligibleItems(member(false, Set.of("facts")));

        assertEquals(1, items.size());
        assertEquals("The Earth orbits the Sun.", items.getFirst().body());
    }

    @Test
    void excludesUnapprovedContent() {
        when(stacks.findById("facts")).thenReturn(Optional.of(
                stack("facts", true, null, Status.DRAFT, false)));

        assertTrue(service.eligibleItems(
                member(false, Set.of("facts"))).isEmpty());
    }

    @Test
    void excludesArchivedStacks() {
        when(stacks.findById("facts")).thenReturn(Optional.of(
                stack("facts", true, null, Status.APPROVED, true)));

        assertTrue(service.eligibleItems(
                member(false, Set.of("facts"))).isEmpty());
    }

    @Test
    void excludesPersonalStacksForFreeMembers() {
        when(stacks.findById("personal")).thenReturn(Optional.of(
                stack("personal", false, "member-1", Status.APPROVED, false)));

        assertTrue(service.eligibleItems(
                member(false, Set.of("personal"))).isEmpty());
    }

    @Test
    void allowsOwnedPersonalStacksForPremiumMembers() {
        when(stacks.findById("personal")).thenReturn(Optional.of(
                stack("personal", false, "member-1", Status.APPROVED, false)));

        assertEquals(1, service.eligibleItems(
                member(true, Set.of("personal"))).size());
    }

    @Test
    void excludesOtherMembersPersonalStacks() {
        when(stacks.findById("personal")).thenReturn(Optional.of(
                stack("personal", false, "another-member", Status.APPROVED, false)));

        assertTrue(service.eligibleItems(
                member(true, Set.of("personal"))).isEmpty());
    }

    @Test
    void returnsNothingWhenMemberHasNoSubscriptions() {
        assertTrue(service.eligibleItems(
                member(false, Set.of())).isEmpty());

        verifyNoInteractions(stacks);
    }

    // SMS CONTENT KEY TESTS

    @Test
    void generatesUniqueKeysForItemsInSameStack() {
        Item first = new Item(
                Type.FACT, "The Earth orbits the Sun.", null, null);

        Item second = new Item(
                Type.FACT, "Jupiter is the largest planet.", null, null);

        Revision version = new Revision(
                2, "Facts", "Educational facts",
                List.of(first, second),
                Status.APPROVED, Instant.now(), null, null
        );

        ContentStack contentStack = new ContentStack(
                "facts", null, true, null, null,
                2, List.of(version), 1L, false
        );

        when(stacks.findById("facts"))
                .thenReturn(Optional.of(contentStack));

        var content = service.eligibleContent(
                member(false, Set.of("facts")));

        assertEquals(2, content.size());
        assertEquals("facts:2:0", content.get(0).contentKey());
        assertEquals("facts:2:1", content.get(1).contentKey());
        assertNotEquals(
                content.get(0).contentKey(),
                content.get(1).contentKey()
        );
    }

    @Test
    void generatesDifferentKeysForDifferentStacks() {
        when(stacks.findById("facts"))
                .thenReturn(Optional.of(
                        stack("facts", true, null, Status.APPROVED, false)));

        when(stacks.findById("science"))
                .thenReturn(Optional.of(
                        stack("science", true, null, Status.APPROVED, false)));

        var content = service.eligibleContent(
                member(false, Set.of("facts", "science")));

        assertEquals(2, content.size());

        var keys = content.stream()
                .map(SmsContentService.EligibleContent::contentKey)
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(Set.of("facts:1:0", "science:1:0"), keys);
    }

    @Test
    void excludesUnapprovedContentFromDeliveryKeys() {
        when(stacks.findById("draft"))
                .thenReturn(Optional.of(
                        stack("draft", true, null, Status.DRAFT, false)));

        when(stacks.findById("approved"))
                .thenReturn(Optional.of(
                        stack("approved", true, null, Status.APPROVED, false)));

        var content = service.eligibleContent(
                member(false, Set.of("draft", "approved")));

        assertEquals(1, content.size());
        assertEquals("approved:1:0", content.getFirst().contentKey());
    }

    @Test
    void generatesContentKeysWhenItemIdsAreMissing() {
        when(stacks.findById("facts"))
                .thenReturn(Optional.of(
                        stack("facts", true, null, Status.APPROVED, false)));

        var content = service.eligibleContent(
                member(false, Set.of("facts")));

        assertEquals(1, content.size());
        assertNull(content.getFirst().item().id());
        assertEquals("facts:1:0", content.getFirst().contentKey());
        assertEquals(
                "The Earth orbits the Sun.",
                content.getFirst().item().body()
        );
    }
}
