package com.bytesolutions.smartifier.sms;

import com.bytesolutions.smartifier.account.AccountService;

import com.bytesolutions.smartifier.account.Account;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SmsPreferenceServiceTest {

    private SmsPreferenceRepository preferences;
    private AccountService accounts;
    private SmsPreferenceService service;

    private final String email = "member@example.com";

    @BeforeEach
    void setUp() {
        preferences = mock(SmsPreferenceRepository.class);
        accounts = mock(AccountService.class);
        service = new SmsPreferenceService(preferences, accounts);

        when(accounts.current(email)).thenReturn(new Account(
                "member-1", "Test Member", email,
                "hash", Account.Role.MEMBER, Instant.now(),
                "+14165551234", Set.of(), false));
    }

    @Test
    void membersWithoutConsentAreNotOptedIn() {
        when(preferences.findById("member-1"))
                .thenReturn(Optional.empty());

        assertFalse(service.isOptedIn(email));
    }

    @Test
    void optingInRecordsConsentTimestamp() {
        when(preferences.findById("member-1"))
                .thenReturn(Optional.empty());

        when(preferences.save(any(SmsPreference.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        SmsPreference result = service.updatePreference(email, true);

        assertTrue(result.optedIn());
        assertNotNull(result.consentedAt());
        assertNotNull(result.updatedAt());
    }

    @Test
    void optingOutDisablesSms() {
        SmsPreference existing = new SmsPreference(
                "member-1", true, Instant.now(), Instant.now());

        when(preferences.findById("member-1"))
                .thenReturn(Optional.of(existing));

        when(preferences.save(any(SmsPreference.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        SmsPreference result = service.updatePreference(email, false);

        assertFalse(result.optedIn());
    }

    @Test
    void adminsCannotChangeSmsPreferences() {
        when(accounts.current("admin@example.com"))
                .thenReturn(new Account(
                        "admin-1", "Admin", "admin@example.com",
                        "hash", Account.Role.ADMIN, Instant.now()));

        assertThrows(
                ResponseStatusException.class,
                () -> service.updatePreference("admin@example.com", true)
        );

        verify(preferences, never()).save(any());
    }

    // SMS TEST: Changing the phone number must revoke consent.
    @Test
    void changingPhoneNumberResetsSmsConsent() {
        SmsPreference existing = new SmsPreference(
                "member-1", true, Instant.now(), Instant.now());

        when(preferences.findById("member-1"))
                .thenReturn(Optional.of(existing));

        when(preferences.save(any(SmsPreference.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.updatePhoneAndResetConsent(email, "+14165559999");

        var savedPreference =
                org.mockito.ArgumentCaptor.forClass(SmsPreference.class);

        verify(preferences).save(savedPreference.capture());

        assertFalse(savedPreference.getValue().optedIn());

        verify(accounts).updatePhone(email, "+14165559999");

        // Consent must be revoked before the new number is saved.
        var order = inOrder(preferences, accounts);
        order.verify(preferences).save(any(SmsPreference.class));
        order.verify(accounts).updatePhone(email, "+14165559999");
    }

    // SMS TEST: Saving the same phone number must preserve consent.
    @Test
    void savingSamePhoneNumberPreservesSmsConsent() {
        service.updatePhoneAndResetConsent(email, "+14165551234");

        verify(accounts).updatePhone(email, "+14165551234");

        // No SMS preference change is necessary.
        verifyNoInteractions(preferences);
    }
}
