package com.bytesolutions.smartifier.sms;

import java.time.Instant;
import java.time.LocalDate;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.annotation.TypeAlias;

@TypeAlias("com.bytesolutions.smartifier.models.SmsDailyClaim")
@Document("sms_daily_claims")
public record SmsDailyClaim(
        @Id String id,
        String accountId,
        LocalDate deliveryDate,
        Instant createdAt
) {}
