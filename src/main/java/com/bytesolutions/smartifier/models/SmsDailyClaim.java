
package com.bytesolutions.smartifier.models;

import java.time.Instant;
import java.time.LocalDate;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("sms_daily_claims")
public record SmsDailyClaim(
        @Id String id,
        String accountId,
        LocalDate deliveryDate,
        Instant createdAt
) {}
