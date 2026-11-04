
package com.bytesolutions.smartifier.services;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import com.bytesolutions.smartifier.models.SmsDelivery;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

@Service
public class SmsDeliveryClaimService {

    private final MongoTemplate mongo;

    public SmsDeliveryClaimService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /**
     * Atomically reserves a message for one member.
     *
     * Returns false if the same member/content combination
     * has already been reserved.
     *
     * This method does not send messages.
     */
    public boolean claim(
            String accountId,
            String contentKey,
            String phoneNumber,
            String message) {

        if (accountId == null || accountId.isBlank()
                || contentKey == null || contentKey.isBlank()
                || phoneNumber == null || phoneNumber.isBlank()
                || message == null || message.isBlank()) {
            throw new IllegalArgumentException(
                    "Complete SMS delivery details are required.");
        }

        Instant now = Instant.now();

        // Deterministic ID ensures the same member/content pair
        // cannot create two MongoDB delivery records.
        String deliveryId = encode(accountId) + "." + encode(contentKey);

        SmsDelivery delivery = new SmsDelivery(
                deliveryId,
                accountId,
                contentKey,
                phoneNumber,
                message,
                SmsDelivery.Status.PENDING,
                now,
                now
        );

        try {
            // A single MongoDB insert is atomic.
            // An existing _id causes a duplicate-key error.
            mongo.insert(delivery);
            return true;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    private String encode(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
