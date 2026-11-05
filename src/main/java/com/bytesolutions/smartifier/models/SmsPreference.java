
package com.bytesolutions.smartifier.models;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("sms_preferences")
public record SmsPreference(
        @Id String accountId,
        boolean optedIn,
        Instant consentedAt,
        Instant updatedAt
) {
}
