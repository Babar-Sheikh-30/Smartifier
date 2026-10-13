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
    void phoneDigitAndInputLengthBoundaries() {
        for (int digits : new int[] {7, 8, 9, 14, 15, 16}) {
            String phone = "+1" + "2".repeat(digits - 1);
            if (digits >= 8 && digits <= 15) {
                assertEquals(phone, AccountService.normalizePhone(phone));
            } else {
                assertThrows(ResponseStatusException.class, () -> AccountService.normalizePhone(phone));
            }
        }
        String phone = "+14165551234";
        assertEquals(phone, AccountService.normalizePhone(phone + " ".repeat(40 - phone.length())));
        assertThrows(ResponseStatusException.class,
                () -> AccountService.normalizePhone(phone + " ".repeat(41 - phone.length())));
        for (String invalid : new String[] {null, "", "   ", "14165551234", "+04165551234", "+14165551234x", "+1\n4165551234"}) {
            assertThrows(ResponseStatusException.class, () -> AccountService.normalizePhone(invalid));
        }
    }

    @Test
    void passwordUtf8LimitAccepts71And72BytesAndRejects73Bytes() {
        for (int bytes : new int[] {71, 72, 73}) {
            String password = "Aa1!" + "\u00e9".repeat(33) + "a".repeat(bytes - 70);
            if (bytes <= 72) assertDoesNotThrow(() -> AccountService.validatePassword(password));
            else assertThrows(ResponseStatusException.class, () -> AccountService.validatePassword(password));
        }
        assertThrows(ResponseStatusException.class, () -> AccountService.validatePassword("Aa1!aaaaaaa"));
    }

    @Test
    void testPasswordValidation_WhenAtOrBeyondLimits_AcceptsOrRejects() {
        // Arrange
        String minimumPassword = "Aa1!" + "a".repeat(8);
        String maximumPassword = "Aa1!" + "a".repeat(60);
        String maximumUtf8Password = "Aa1!" + "\u00e9".repeat(34);
        String oversizedPassword = "Aa1!" + "a".repeat(61);
        String oversizedUtf8Password = "Aa1!" + "\u00e9".repeat(35);

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
    void passwordRequiresEveryCharacterType() {
        for (String invalid : new String[] {
                "lowercase123!", "UPPERCASE123!", "NoNumbersHere!",
                "NoSymbols1234", "SpacesOnly123 " }) {
            assertThrows(ResponseStatusException.class,
                    () -> AccountService.validatePassword(invalid), invalid);
        }
        assertThrows(ResponseStatusException.class, () -> AccountService.validatePassword(null));
        assertDoesNotThrow(() -> AccountService.validatePassword("ValidPassword123!"));
    }

    @Test
    void testRegistration_WhenPasswordIsInvalid_DoesNotWriteAccount() {
        // Arrange
        String name = "Member";
        String email = "member@example.com";
        String password = "short";

        // Act
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.register(name, email, password, "+14165551234"));

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
