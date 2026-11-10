package com.bytesolutions.smartifier.sms;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface SmsDeliveryRepository
        extends MongoRepository<SmsDelivery, String> {

    Optional<SmsDelivery> findByAccountIdAndContentKey(
            String accountId,
            String contentKey
    );
}
