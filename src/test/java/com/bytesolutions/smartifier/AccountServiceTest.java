package com.bytesolutions.smartifier;

import java.time.Instant;
import java.util.Optional;
import com.bytesolutions.smartifier.models.Account;
import com.bytesolutions.smartifier.models.Account.Role;
import com.bytesolutions.smartifier.repositories.AccountRepository;
import com.bytesolutions.smartifier.services.AccountService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AccountServiceTest {
    private final AccountRepository repository = mock(AccountRepository.class);
    private final org.springframework.security.crypto.password.PasswordEncoder passwords =
            PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private final AccountService service = new AccountService(repository, passwords, mock(org.springframework.data.mongodb.core.MongoTemplate.class));

    @Test
    void registrationNormalizesEmailHashesPasswordAndAlwaysCreatesMember() {
        when(repository.insert(any(Account.class))).thenAnswer(call -> call.getArgument(0));
        Account account = service.register("  Test Member  ", " MEMBER@Example.com ", "a-long-test-password", "+1 (416) 555-1234");
        assertEquals("member@example.com", account.email());
        assertEquals("Test Member", account.name());
        assertEquals("+14165551234", account.phoneNumber());
        assertTrue(account.subscriptions().isEmpty());
        assertEquals(Role.MEMBER, account.role());
        assertNotEquals("a-long-test-password", account.passwordHash());
        assertTrue(passwords.matches("a-long-test-password", account.passwordHash()));
    }

    @Test
    void phoneRequiresCountryCodeAndRejectsInvalidNumbers() {
        for (String invalid : new String[] {"4165551234", "+0123456789", "+123", "+1416abc1234", ""}) {
            assertThrows(ResponseStatusException.class,
                    () -> service.register("Member", "member@example.com", "a-long-test-password", invalid));
        }
        assertThrows(ResponseStatusException.class, () -> AccountService.normalizePhone(null));
        verifyNoInteractions(repository);
    }

    @Test
    void concurrentDuplicateRegistrationReturnsConflict() {
        when(repository.insert(any(Account.class))).thenThrow(new DuplicateKeyException("email"));
        var error = assertThrows(ResponseStatusException.class,
                () -> service.register("Member", "member@example.com", "a-long-test-password", "+14165551234"));
        assertEquals(409, error.getStatusCode().value());
    }

    @Test
    void passwordLimitsRejectShortBlankAndOversizedUtf8Passwords() {
        assertThrows(ResponseStatusException.class, () -> AccountService.validatePassword("short"));
        assertThrows(ResponseStatusException.class, () -> AccountService.validatePassword(" ".repeat(12)));
        assertThrows(ResponseStatusException.class, () -> AccountService.validatePassword("é".repeat(40)));
        verifyNoInteractions(repository);
    }

    @Test
    void loginLooksUpNormalizedEmailAndReturnsStoredRole() {
        when(repository.findByEmail("admin@example.com")).thenReturn(Optional.of(
                new Account("1", "Admin", "admin@example.com", "hash", Role.ADMIN, Instant.now())));
        var principal = service.loadUserByUsername(" ADMIN@Example.com ");
        assertEquals("admin@example.com", principal.getUsername());
        assertTrue(principal.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
    }
}
