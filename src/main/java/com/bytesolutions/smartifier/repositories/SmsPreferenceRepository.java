
package com.bytesolutions.smartifier.repositories;

import com.bytesolutions.smartifier.models.SmsPreference;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface SmsPreferenceRepository
        extends MongoRepository<SmsPreference, String> {
}
