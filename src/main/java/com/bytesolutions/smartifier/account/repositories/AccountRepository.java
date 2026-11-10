package com.bytesolutions.smartifier.account.repositories;

import com.bytesolutions.smartifier.account.models.Account;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AccountRepository extends MongoRepository<Account, String> {
    Optional<Account> findByEmail(String email);
}
