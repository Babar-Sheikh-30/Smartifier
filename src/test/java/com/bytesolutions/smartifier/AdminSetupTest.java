package com.bytesolutions.smartifier;

import com.bytesolutions.smartifier.bootstrap.AccountBootstrap;
import com.bytesolutions.smartifier.models.Account;
import com.bytesolutions.smartifier.models.Account.Role;
import com.bytesolutions.smartifier.repositories.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AdminSetupTest {

    @Mock
    private MongoTemplate mongo;

    @Mock
    private IndexOperations indexes;

    @Mock
    private AccountRepository accounts;

    @Mock
    private PasswordEncoder passwords;

    @Mock
    private ApplicationArguments arguments;

    @BeforeEach
    void setUp() {
        when(mongo.indexOps(Account.class)).thenReturn(indexes);
    }

    @Test
    void testAdminSetup_WhenEmailIsBlank_SkipsAccountCreation() {
        // Arrange
        AccountBootstrap bootstrap = new AccountBootstrap(mongo, accounts, passwords,
                "   ", "valid-password-123");

        // Act
        bootstrap.run(arguments);

        // Assert
        verify(indexes).createIndex(any());
        verifyNoInteractions(accounts, passwords);
    }

    @Test
    void testAdminSetup_WhenEmailIsInvalid_RejectsSetup() {
        // Arrange
        AccountBootstrap bootstrap = new AccountBootstrap(mongo, accounts, passwords,
                "invalid-email", "valid-password-123");

        // Act
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> bootstrap.run(arguments));

        // Assert
        assertEquals("SMARTIFIER_ADMIN_EMAIL must be a valid email address.", error.getMessage());
        verifyNoInteractions(accounts, passwords);
    }

    @Test
    void testAdminSetup_WhenEmailBelongsToMember_RejectsPromotion() {
        // Arrange
        Account member = new Account("member-1", "Member", "member@example.com",
                "original-password-hash", Role.MEMBER, Instant.parse("2026-01-01T00:00:00Z"));
        when(accounts.findByEmail("member@example.com")).thenReturn(Optional.of(member));
        AccountBootstrap bootstrap = new AccountBootstrap(mongo, accounts, passwords,
                " MEMBER@Example.com ", "valid-password-123");

        // Act
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> bootstrap.run(arguments));

        // Assert
        assertEquals("The configured admin email belongs to a member. Use another email.",
                error.getMessage());
        verify(accounts).findByEmail("member@example.com");
        verifyNoMoreInteractions(accounts);
        verifyNoInteractions(passwords);
    }
}
