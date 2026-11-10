package com.bytesolutions.smartifier.sms.repositories;

import com.bytesolutions.smartifier.sms.models.SmsPreference;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface SmsPreferenceRepository
        extends MongoRepository<SmsPreference, String> {
}
