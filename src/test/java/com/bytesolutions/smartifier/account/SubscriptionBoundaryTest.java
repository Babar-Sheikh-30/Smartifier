package com.bytesolutions.smartifier.account;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import com.bytesolutions.smartifier.content.ContentStack;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SubscriptionBoundaryTest {
    private final AccountRepository repository = mock(AccountRepository.class);
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final AccountService service = new AccountService(repository, mock(PasswordEncoder.class), mongo);

    private void accountWithPhone(String phone) {
        account(false, phone);
    }

    private void account(boolean premium, String phone) {
        when(repository.findByEmail("member@example.com")).thenReturn(Optional.of(
                new Account("1", "Member", "member@example.com", "hash", Account.Role.MEMBER,
                        Instant.EPOCH, phone, Set.of(), premium)));
        for (String id : new String[] {"everyday-science", "words-and-language", "learning-habits", "new-admin-stack"}) {
            when(mongo.findById(id, ContentStack.class)).thenReturn(new ContentStack(id, null, true, null, null, 1,
                    java.util.List.of(new ContentStack.Revision(1, "Shared", "", java.util.List.of(), ContentStack.Status.APPROVED,
                            Instant.EPOCH, null, null)), 0L));
        }
    }

    @Test
    void everyStackSupportsAtomicSubscribeAndUnsubscribe() {
        accountWithPhone("+14165551234");
        for (String stack : new String[] {"everyday-science", "words-and-language", "learning-habits", "new-admin-stack"}) {
            for (boolean subscribed : new boolean[] {true, false}) {
                service.subscribe("member@example.com", stack, subscribed);
                var query = org.mockito.ArgumentCaptor.forClass(Query.class);
                var update = org.mockito.ArgumentCaptor.forClass(Update.class);
                verify(mongo).updateFirst(query.capture(), update.capture(), eq(Account.class));
                assertEquals("1", query.getValue().getQueryObject().getString("id"));
                var operation = update.getValue().getUpdateObject().get(subscribed ? "$addToSet" : "$pull", org.bson.Document.class);
                assertEquals(stack, operation.getString("subscriptions"));
                clearInvocations(mongo);
            }
        }
    }

    @Test
    void freeMembersCannotSubscribeToUnsupportedOrPersonalStacks() {
        accountWithPhone("+14165551234");
        for (String stack : new String[] {"", " ", "unknown", "Everyday-science", "everyday-science "}) {
            var error = assertThrows(ResponseStatusException.class,
                    () -> service.subscribe("member@example.com", stack, true));
            assertEquals(400, error.getStatusCode().value());
        }
        verify(mongo, never()).updateFirst(any(Query.class), any(Update.class), eq(Account.class));
    }

    @Test
    void premiumMemberCanSubscribeAndUnsubscribeOnlyToOwnedPersonalStacks() {
        account(true, "+14165551234");
        when(mongo.exists(any(Query.class), eq(ContentStack.class))).thenReturn(true);
        for (boolean subscribed : new boolean[] {true, false}) {
            service.subscribe("member@example.com", "personal-1", subscribed);
            var ownership = org.mockito.ArgumentCaptor.forClass(Query.class);
            verify(mongo).exists(ownership.capture(), eq(ContentStack.class));
            assertEquals("personal-1", ownership.getValue().getQueryObject().getString("id"));
            assertEquals("1", ownership.getValue().getQueryObject().getString("ownerId"));
            assertEquals(false, ownership.getValue().getQueryObject().getBoolean("defaultStack"));
            var update = org.mockito.ArgumentCaptor.forClass(Update.class);
            verify(mongo).updateFirst(any(Query.class), update.capture(), eq(Account.class));
            assertEquals("personal-1", update.getValue().getUpdateObject()
                    .get(subscribed ? "$addToSet" : "$pull", org.bson.Document.class).getString("subscriptions"));
            clearInvocations(mongo);
        }
    }

    @Test
    void missingOrOtherMembersStacksCannotBeSubscribedToOrUnsubscribedFrom() {
        account(true, "+14165551234");
        when(mongo.exists(any(Query.class), eq(ContentStack.class))).thenReturn(false);
        for (boolean subscribed : new boolean[] {true, false}) {
            assertEquals(400, assertThrows(ResponseStatusException.class,
                    () -> service.subscribe("member@example.com", "not-owned", subscribed)).getStatusCode().value());
        }
        verify(mongo, never()).updateFirst(any(Query.class), any(Update.class), eq(Account.class));
    }

    @Test
    void personalSubscriptionsNeedPhoneButOwnersCanRemoveThemAfterDowngrade() {
        account(true, null);
        when(mongo.exists(any(Query.class), eq(ContentStack.class))).thenReturn(true);
        assertThrows(ResponseStatusException.class,
                () -> service.subscribe("member@example.com", "personal-1", true));
        verify(mongo, never()).updateFirst(any(Query.class), any(Update.class), eq(Account.class));
        account(false, null);
        assertDoesNotThrow(() -> service.subscribe("member@example.com", "personal-1", false));
        verify(mongo).updateFirst(any(Query.class), any(Update.class), eq(Account.class));
    }

    @Test
    void legacyAccountNeedsPhoneToSubscribeButCanUnsubscribe() {
        accountWithPhone(null);
        assertThrows(ResponseStatusException.class,
                () -> service.subscribe("member@example.com", "everyday-science", true));
        verifyNoInteractions(mongo);
        assertDoesNotThrow(() -> service.subscribe("member@example.com", "everyday-science", false));
        verify(mongo).updateFirst(any(Query.class), any(Update.class), eq(Account.class));
    }

    @Test
    void phoneUpdateNormalizesValidNumberAndRejectsInvalidBeforeDatabaseAccess() {
        assertThrows(ResponseStatusException.class, () -> service.updatePhone("member@example.com", "123"));
        verifyNoInteractions(repository, mongo);
        accountWithPhone(null);
        service.updatePhone("member@example.com", "+1 (647) 555-1234");
        var update = org.mockito.ArgumentCaptor.forClass(Update.class);
        verify(mongo).updateFirst(any(Query.class), update.capture(), eq(Account.class));
        assertEquals("+16475551234", update.getValue().getUpdateObject().get("$set", org.bson.Document.class).getString("phoneNumber"));
    }
}
