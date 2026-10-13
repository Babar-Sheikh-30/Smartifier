package com.bytesolutions.smartifier;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import com.bytesolutions.smartifier.models.Account;
import com.bytesolutions.smartifier.repositories.AccountRepository;
import com.bytesolutions.smartifier.services.AccountService;
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
        when(repository.findByEmail("member@example.com")).thenReturn(Optional.of(
                new Account("1", "Member", "member@example.com", "hash", Account.Role.MEMBER,
                        Instant.EPOCH, phone, Set.of())));
    }

    @Test
    void everyStackSupportsAtomicSubscribeAndUnsubscribe() {
        accountWithPhone("+14165551234");
        for (String stack : new String[] {"everyday-science", "words-and-language", "learning-habits"}) {
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
    void unsupportedStacksRejectWithoutReadingOrWritingAccount() {
        for (String stack : new String[] {"", " ", "unknown", "Everyday-science", "everyday-science "}) {
            var error = assertThrows(ResponseStatusException.class,
                    () -> service.subscribe("member@example.com", stack, true));
            assertEquals(400, error.getStatusCode().value());
        }
        verifyNoInteractions(repository, mongo);
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
        assertThrows(ResponseStatusException.class, () -> service.updatePhone("member@example.com", "4165551234"));
        verifyNoInteractions(repository, mongo);
        accountWithPhone(null);
        service.updatePhone("member@example.com", "+1 (647) 555-1234");
        var update = org.mockito.ArgumentCaptor.forClass(Update.class);
        verify(mongo).updateFirst(any(Query.class), update.capture(), eq(Account.class));
        assertEquals("+16475551234", update.getValue().getUpdateObject().get("$set", org.bson.Document.class).getString("phoneNumber"));
    }
}
