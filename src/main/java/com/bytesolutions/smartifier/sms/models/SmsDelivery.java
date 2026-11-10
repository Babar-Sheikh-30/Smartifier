package com.bytesolutions.smartifier.sms.models;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.annotation.TypeAlias;

@TypeAlias("com.bytesolutions.smartifier.models.SmsDelivery")
@Document(collection = "sms_deliveries")
@CompoundIndex(
        name = "unique_member_content",
        def = "{'accountId': 1, 'contentKey': 1}",
        unique = true
)
public record SmsDelivery(
        @Id String id,
        String accountId,
        String contentKey,
        String phoneNumber,
        String message,
        Status status,
        Instant createdAt,
        Instant updatedAt
) {
    public enum Status {
        PENDING,
        SENT,
        FAILED
    }
}
