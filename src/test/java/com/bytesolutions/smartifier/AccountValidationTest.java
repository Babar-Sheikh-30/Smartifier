package com.bytesolutions.smartifier;

import com.bytesolutions.smartifier.models.Account;
import com.bytesolutions.smartifier.models.Account.Role;
import com.bytesolutions.smartifier.repositories.AccountRepository;
import com.bytesolutions.smartifier.services.AccountService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AccountValidationTest {

    @Mock
    private AccountRepository repository;

    @Mock
    private PasswordEncoder passwords;

    @InjectMocks
    private AccountService service;

    @Test
    void testPasswordValidation_WhenAtOrBeyondLimits_AcceptsOrRejects() {
        // Arrange
        String minimumPassword = "a".repeat(12);
        String maximumPassword = "a".repeat(64);
        String maximumUtf8Password = "\u00e9".repeat(36);
        String oversizedPassword = "a".repeat(65);
        String oversizedUtf8Password = "\u00e9".repeat(37);

        // Act / Assert
        assertDoesNotThrow(() -> AccountService.validatePassword(minimumPassword));
        assertDoesNotThrow(() -> AccountService.validatePassword(maximumPassword));
        assertDoesNotThrow(() -> AccountService.validatePassword(maximumUtf8Password));
        assertThrows(ResponseStatusException.class,
                () -> AccountService.validatePassword(oversizedPassword));
        assertThrows(ResponseStatusException.class,
                () -> AccountService.validatePassword(oversizedUtf8Password));
    }

    @Test
    void testRegistration_WhenPasswordIsInvalid_DoesNotWriteAccount() {
        // Arrange
        String name = "Member";
        String email = "member@example.com";
        String password = "short";

        // Act
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.register(name, email, password));

        // Assert
        assertEquals(400, error.getStatusCode().value());
        verifyNoInteractions(repository, passwords);
    }

    @Test
    void testCurrentAccount_WhenEmailHasSpacesAndUppercase_ReturnsAccount() {
        // Arrange
        Account account = new Account("1", "Member", "member@example.com", "hash",
                Role.MEMBER, Instant.parse("2026-01-01T00:00:00Z"));
        when(repository.findByEmail("member@example.com")).thenReturn(Optional.of(account));

        // Act
        Account result = service.current(" MEMBER@Example.com ");

        // Assert
        assertSame(account, result);
        verify(repository).findByEmail("member@example.com");
    }

    @Test
    void testCurrentAccount_WhenAccountIsMissing_ReturnsUnauthorized() {
        // Arrange
        when(repository.findByEmail("missing@example.com")).thenReturn(Optional.empty());

        // Act
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.current("missing@example.com"));

        // Assert
        assertEquals(401, error.getStatusCode().value());
    }

    @Test
    void testLoginLookup_WhenEmailIsUnknown_RejectsLogin() {
        // Arrange
        when(repository.findByEmail("missing@example.com")).thenReturn(Optional.empty());

        // Act
        UsernameNotFoundException error = assertThrows(UsernameNotFoundException.class,
                () -> service.loadUserByUsername(" MISSING@Example.com "));

        // Assert
        assertEquals("Invalid email or password.", error.getMessage());
        verify(repository).findByEmail("missing@example.com");
    }
}
