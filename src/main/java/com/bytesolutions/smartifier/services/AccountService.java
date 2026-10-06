package com.bytesolutions.smartifier.services;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import com.bytesolutions.smartifier.models.Account;
import com.bytesolutions.smartifier.models.Account.Role;
import com.bytesolutions.smartifier.repositories.AccountRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountService implements UserDetailsService {
    private final AccountRepository accounts;
    private final PasswordEncoder passwords;
    private final org.springframework.data.mongodb.core.MongoTemplate mongo;

    public AccountService(AccountRepository accounts, PasswordEncoder passwords,
                          org.springframework.data.mongodb.core.MongoTemplate mongo) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.mongo = mongo;
    }

    public static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    public static void validatePassword(String password) {
        if (password.length() < 12 || password.length() > 64
                || password.getBytes(StandardCharsets.UTF_8).length > 72 || password.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Use a password of 12–64 characters, up to 72 UTF-8 bytes.");
        }
    }

    public static String normalizePhone(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.length() > 40
                || !phoneNumber.matches("[+0-9() .-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a mobile number with country code, such as +14165551234.");
        }
        String normalized = phoneNumber.replaceAll("[() .-]", "");
        if (!normalized.matches("\\+[1-9][0-9]{7,14}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a mobile number with country code, such as +14165551234.");
        }
        return normalized;
    }

    public Account register(String name, String email, String password, String phoneNumber) {
        validatePassword(password);
        String phone = normalizePhone(phoneNumber);
        try {
            // The database's unique email index also prevents concurrent duplicate registrations.
            return accounts.insert(new Account(null, name.strip(), normalizeEmail(email),
                    passwords.encode(password), Role.MEMBER, Instant.now(), phone, Set.of()));
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "An account with this email already exists.");
        }
    }

    public Account subscribe(String email, String stack, boolean subscribed) {
        if (!Set.of("everyday-science", "words-and-language", "learning-habits").contains(stack)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose one of the three available stacks.");
        }
        Account account = current(email);
        if (subscribed) normalizePhone(account.phoneNumber());
        // Update just this stack so simultaneous requests cannot overwrite other subscriptions.
        var query = org.springframework.data.mongodb.core.query.Query.query(
                org.springframework.data.mongodb.core.query.Criteria.where("id").is(account.id()));
        var update = new org.springframework.data.mongodb.core.query.Update();
        if (subscribed) update.addToSet("subscriptions", stack);
        else update.pull("subscriptions", stack);
        mongo.updateFirst(query, update, Account.class);
        return current(email);
    }

    public Account updatePhone(String email, String phoneNumber) {
        String phone = normalizePhone(phoneNumber);
        Account account = current(email);
        mongo.updateFirst(org.springframework.data.mongodb.core.query.Query.query(
                org.springframework.data.mongodb.core.query.Criteria.where("id").is(account.id())),
                new org.springframework.data.mongodb.core.query.Update().set("phoneNumber", phone), Account.class);
        return current(email);
    }

    public Account current(String email) {
        return accounts.findByEmail(normalizeEmail(email))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in."));
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        Account account = accounts.findByEmail(normalizeEmail(email))
                .orElseThrow(() -> new UsernameNotFoundException("Invalid email or password."));
        return User.withUsername(account.email()).password(account.passwordHash())
                .roles(account.role().name()).build();
    }
}
