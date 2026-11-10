package com.bytesolutions.smartifier.sms.unit;

import com.bytesolutions.smartifier.sms.models.SmsDelivery;
import com.bytesolutions.smartifier.sms.services.SmsDeliveryClaimService;


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SmsDeliveryClaimServiceTest {

    private MongoTemplate mongo;
    private SmsDeliveryClaimService service;

    @BeforeEach
    void setUp() {
        mongo = mock(MongoTemplate.class);
        service = new SmsDeliveryClaimService(mongo);
    }

    @Test
    void successfullyClaimsNewMessage() {
        boolean claimed = service.claim(
                "member-1",
                "facts:1:0",
                "+14165551234",
                "Smartifier - FACT\nThe Earth orbits the Sun."
        );

        assertTrue(claimed);

        var captor = org.mockito.ArgumentCaptor.forClass(SmsDelivery.class);
        verify(mongo).insert(captor.capture());

        SmsDelivery saved = captor.getValue();

        assertEquals("member-1", saved.accountId());
        assertEquals("facts:1:0", saved.contentKey());
        assertEquals("+14165551234", saved.phoneNumber());
        assertEquals(SmsDelivery.Status.PENDING, saved.status());
        assertNotNull(saved.id());
        assertNotNull(saved.createdAt());
        assertNotNull(saved.updatedAt());
    }

    @Test
    void duplicateClaimReturnsFalse() {
        when(mongo.insert(any(SmsDelivery.class)))
                .thenThrow(new DuplicateKeyException("Duplicate delivery"));

        boolean claimed = service.claim(
                "member-1",
                "facts:1:0",
                "+14165551234",
                "Smartifier - FACT\nThe Earth orbits the Sun."
        );

        assertFalse(claimed);
        verify(mongo).insert(any(SmsDelivery.class));
    }

    @Test
    void sameMemberAndContentProduceSameDeliveryId() {
        service.claim(
                "member-1", "facts:1:0",
                "+14165551234", "First message"
        );

        service.claim(
                "member-1", "facts:1:0",
                "+14165559999", "Second message"
        );

        var captor = org.mockito.ArgumentCaptor.forClass(SmsDelivery.class);
        verify(mongo, times(2)).insert(captor.capture());

        var records = captor.getAllValues();

        assertEquals(records.get(0).id(), records.get(1).id());
    }

    @Test
    void differentContentProducesDifferentDeliveryIds() {
        service.claim(
                "member-1", "facts:1:0",
                "+14165551234", "First message"
        );

        service.claim(
                "member-1", "facts:1:1",
                "+14165551234", "Second message"
        );

        var captor = org.mockito.ArgumentCaptor.forClass(SmsDelivery.class);
        verify(mongo, times(2)).insert(captor.capture());

        var records = captor.getAllValues();

        assertNotEquals(records.get(0).id(), records.get(1).id());
    }

    @Test
    void rejectsIncompleteDeliveryDetails() {
        assertThrows(IllegalArgumentException.class,
                () -> service.claim(
                        "", "facts:1:0",
                        "+14165551234", "Message"
                ));

        assertThrows(IllegalArgumentException.class,
                () -> service.claim(
                        "member-1", "",
                        "+14165551234", "Message"
                ));

        assertThrows(IllegalArgumentException.class,
                () -> service.claim(
                        "member-1", "facts:1:0",
                        "", "Message"
                ));

        assertThrows(IllegalArgumentException.class,
                () -> service.claim(
                        "member-1", "facts:1:0",
                        "+14165551234", ""
                ));

        verifyNoInteractions(mongo);
    }
}
