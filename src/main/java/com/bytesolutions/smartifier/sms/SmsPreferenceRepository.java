package com.bytesolutions.smartifier.sms;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface SmsPreferenceRepository
        extends MongoRepository<SmsPreference, String> {
}
