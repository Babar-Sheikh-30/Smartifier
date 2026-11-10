package com.bytesolutions.smartifier.sms.models;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.annotation.TypeAlias;

@TypeAlias("com.bytesolutions.smartifier.models.SmsPreference")
@Document("sms_preferences")
public record SmsPreference(
        @Id String accountId,
        boolean optedIn,
        Instant consentedAt,
        Instant updatedAt
) {
}
