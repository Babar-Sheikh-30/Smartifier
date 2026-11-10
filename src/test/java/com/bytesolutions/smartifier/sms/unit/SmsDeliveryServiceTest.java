package com.bytesolutions.smartifier.sms.unit;

import com.bytesolutions.smartifier.sms.services.SmsContentService;
import com.bytesolutions.smartifier.sms.services.SmsDeliveryService;
import com.bytesolutions.smartifier.sms.models.SmsPreference;
import com.bytesolutions.smartifier.sms.repositories.SmsPreferenceRepository;

import com.bytesolutions.smartifier.account.models.Account;
import com.bytesolutions.smartifier.content.models.ContentStack.Item;
import com.bytesolutions.smartifier.content.models.ContentStack.Type;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SmsDeliveryServiceTest {

    private SmsContentService contentService;
    private SmsPreferenceRepository preferences;
    private SmsDeliveryService service;

    private Account member;

    @BeforeEach
    void setUp() {
        contentService = mock(SmsContentService.class);
        preferences = mock(SmsPreferenceRepository.class);

        service = new SmsDeliveryService(contentService, preferences);

        member = new Account(
                "member-1",
                "Test Member",
                "member@example.com",
                "hash",
                Account.Role.MEMBER,
                Instant.now(),
                "+14165551234",
                Set.of("everyday-vocabulary"),
                false
        );
    }

    @Test
    void optedInMemberWithEligibleContentGetsPreparedMessage() {
        SmsPreference consent = new SmsPreference(
                "member-1",
                true,
                Instant.now(),
                Instant.now()
        );

        when(preferences.findById("member-1"))
                .thenReturn(Optional.of(consent));

        Item item = new Item(
                Type.VOCABULARY,
                "Eloquent means fluent and persuasive in speaking or writing.",
                null,
                null
        );

        when(contentService.eligibleItems(member))
                .thenReturn(List.of(item));

        Optional<String> result = service.prepareMessage(member);

        assertTrue(result.isPresent());

        assertEquals(
                "Smartifier - VOCABULARY\n"
                        + "Eloquent means fluent and persuasive in speaking or writing.",
                result.get()
        );

        verify(preferences).findById("member-1");
        verify(contentService).eligibleItems(member);
    }

    @Test
    void memberWithoutSmsConsentGetsNoMessage() {
        when(preferences.findById("member-1"))
                .thenReturn(Optional.empty());

        Optional<String> result = service.prepareMessage(member);

        assertTrue(result.isEmpty());

        verify(preferences).findById("member-1");
        verifyNoInteractions(contentService);
    }

    @Test
    void memberWithoutPhoneNumberGetsNoMessage() {
        Account memberWithoutPhone = new Account(
                "member-1",
                "Test Member",
                "member@example.com",
                "hash",
                Account.Role.MEMBER,
                Instant.now(),
                null,
                Set.of("everyday-vocabulary"),
                false
        );

        Optional<String> result =
                service.prepareMessage(memberWithoutPhone);

        assertTrue(result.isEmpty());

        verifyNoInteractions(preferences, contentService);
    }

    @Test
    void memberWithoutEligibleContentGetsNoMessage() {
        SmsPreference consent = new SmsPreference(
                "member-1",
                true,
                Instant.now(),
                Instant.now()
        );

        when(preferences.findById("member-1"))
                .thenReturn(Optional.of(consent));

        when(contentService.eligibleItems(member))
                .thenReturn(List.of());

        Optional<String> result = service.prepareMessage(member);

        assertTrue(result.isEmpty());

        verify(preferences).findById("member-1");
        verify(contentService).eligibleItems(member);
    }

    @Test
    void explicitlyOptedOutMemberGetsNoMessage() {
        SmsPreference consent = new SmsPreference(
                "member-1",
                false,
                Instant.now(),
                Instant.now()
        );

        when(preferences.findById("member-1"))
                .thenReturn(Optional.of(consent));

        Optional<String> result = service.prepareMessage(member);

        assertTrue(result.isEmpty());

        verifyNoInteractions(contentService);
    }

    @Test
    void nullAccountGetsNoMessage() {
        Optional<String> result = service.prepareMessage(null);

        assertTrue(result.isEmpty());

        verifyNoInteractions(preferences, contentService);
    }
}
