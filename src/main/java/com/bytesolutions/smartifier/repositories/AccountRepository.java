package com.bytesolutions.smartifier.repositories;

import java.util.Optional;
import com.bytesolutions.smartifier.models.Account;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AccountRepository extends MongoRepository<Account, String> {
    Optional<Account> findByEmail(String email);
}
