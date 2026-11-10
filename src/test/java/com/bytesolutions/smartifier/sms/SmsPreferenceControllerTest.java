package com.bytesolutions.smartifier.sms;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SmsPreferenceControllerTest {

    private SmsPreferenceService preferences;
    private SmsPreferenceController controller;
    private Principal principal;

    @BeforeEach
    void setUp() {
        preferences = mock(SmsPreferenceService.class);
        controller = new SmsPreferenceController(preferences);
        principal = () -> "member@example.com";
    }

    @Test
    void getsCurrentSmsPreference() {
        when(preferences.isOptedIn("member@example.com"))
                .thenReturn(true);

        var response = controller.getSettings(principal);

        assertTrue(response.optedIn());
        verify(preferences).isOptedIn("member@example.com");
    }

    @Test
    void enablesSmsForAuthenticatedMember() {
        when(preferences.updatePreference("member@example.com", true))
                .thenReturn(new SmsPreference(
                        "member-1", true, Instant.now(), Instant.now()));

        var request = new SmsPreferenceController.UpdateSmsSettings(true);
        var response = controller.updateSettings(principal, request);

        assertTrue(response.optedIn());
        verify(preferences).updatePreference("member@example.com", true);
    }

    @Test
    void disablesSmsForAuthenticatedMember() {
        when(preferences.updatePreference("member@example.com", false))
                .thenReturn(new SmsPreference(
                        "member-1", false, Instant.now(), Instant.now()));

        var request = new SmsPreferenceController.UpdateSmsSettings(false);
        var response = controller.updateSettings(principal, request);

        assertFalse(response.optedIn());
        verify(preferences).updatePreference("member@example.com", false);
    }
}
