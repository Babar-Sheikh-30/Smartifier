package com.bytesolutions.smartifier.bootstrap;

import java.time.Instant;
import com.bytesolutions.smartifier.models.Account;
import com.bytesolutions.smartifier.models.Account.Role;
import com.bytesolutions.smartifier.repositories.AccountRepository;
import com.bytesolutions.smartifier.services.AccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AccountBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AccountBootstrap.class);
    private final MongoTemplate mongo;
    private final AccountRepository accounts;
    private final PasswordEncoder passwords;
    private final String email;
    private final String password;

    public AccountBootstrap(MongoTemplate mongo, AccountRepository accounts, PasswordEncoder passwords,
                            @Value("${smartifier.admin.email}") String email,
                            @Value("${smartifier.admin.password}") String password) {
        this.mongo = mongo;
        this.accounts = accounts;
        this.passwords = passwords;
        this.email = email;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        mongo.indexOps(Account.class).createIndex(new Index().on("email", Sort.Direction.ASC)
                .unique().named("unique_account_email"));
        if (email.isBlank()) {
            log.info("To create the initial admin, run .\\start-local.ps1 -SetupAdmin.");
            return;
        }
        String normalized = AccountService.normalizeEmail(email);
        if (!normalized.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+") || normalized.length() > 254) {
            throw new IllegalStateException("SMARTIFIER_ADMIN_EMAIL must be a valid email address.");
        }
        var existing = accounts.findByEmail(normalized);
        if (existing.isPresent()) {
            if (existing.get().role() != Role.ADMIN) {
                throw new IllegalStateException("The configured admin email belongs to a member. Use another email.");
            }
            log.info("Initial admin already exists; credentials remain unchanged.");
            return;
        }
        AccountService.validatePassword(password);
        accounts.insert(new Account(null, "Administrator", normalized,
                passwords.encode(password), Role.ADMIN, Instant.now()));
        log.info("Initial admin account created.");
    }
}
