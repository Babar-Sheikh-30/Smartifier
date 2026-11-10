package com.bytesolutions.smartifier.account.services;

import com.bytesolutions.smartifier.account.models.Account;
import com.bytesolutions.smartifier.account.repositories.AccountRepository;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import com.bytesolutions.smartifier.account.models.Account.Role;
import com.bytesolutions.smartifier.content.models.ContentStack;
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
        // Registration and login use the same key regardless of casing or surrounding spaces.
        return email.strip().toLowerCase(Locale.ROOT);
    }

    public static void validatePassword(String password) {
        // BCrypt limits input to 72 bytes; Unicode characters can use more than one byte.
        if (password == null || password.length() < 12 || password.length() > 64
                || password.getBytes(StandardCharsets.UTF_8).length > 72
                || !password.matches("(?s).*[A-Z].*")
                || !password.matches("(?s).*[a-z].*")
                || !password.matches("(?s).*[0-9].*")
                || !password.matches("(?s).*[^A-Za-z0-9\\s].*")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Use 12–64 characters with an uppercase letter, a lowercase letter, a number, and a special character (up to 72 UTF-8 bytes).");
        }
    }

    public static String normalizePhone(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.length() > 40
                || !phoneNumber.matches("[+0-9() .-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a mobile number with country code, such as +14165551234.");
        }
        // Accept common display formatting, but store one international form for messaging.
        String normalized = phoneNumber.replaceAll("[() .-]", "");
        if (!normalized.startsWith("+")) normalized = "+" + normalized;
        if (!normalized.matches("\\+[1-9][0-9]{7,14}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a mobile number with country code, such as +14165551234.");
        }
        return normalized;
    }

    public Account register(String name, String email, String password, String phoneNumber) {
        validatePassword(password);
        // Regular members need a valid phone number for stack messages; admins are created separately.
        String phone = normalizePhone(phoneNumber);
        try {
            // The database's unique email index also prevents concurrent duplicate registrations.
            return accounts.insert(new Account(null, name.strip(), normalizeEmail(email),
                    passwords.encode(password), Role.MEMBER, null, phone, Set.of()));
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "An account with this email already exists.");
        }
    }

    public Account subscribe(String email, String stack, boolean subscribed) {
        if (stack == null || stack.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose an available stack.");
        }
        Account account = current(email);
        // Legacy accounts must add a valid messaging number before subscribing.
        if (subscribed) normalizePhone(account.phoneNumber());
        var shared = mongo.findById(stack, ContentStack.class);
        boolean defaultStack = shared != null && shared.defaultStack() && !shared.archived()
                && shared.publishedVersion() != null;
        boolean stoppingExisting = !subscribed && account.subscriptions().contains(stack);
        // Personal stacks must belong to this member. Unsubscribing remains possible
        // without premium so a member can always stop an existing subscription.
        if (!stoppingExisting && !defaultStack && !(account.role() == Role.MEMBER && (!subscribed || account.premium())
                && mongo.exists(org.springframework.data.mongodb.core.query.Query.query(
                        org.springframework.data.mongodb.core.query.Criteria.where("id").is(stack)
                                .and("ownerId").is(account.id()).and("defaultStack").is(false)
                                .and("archived").ne(true)), ContentStack.class))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Choose a default stack or one of your own premium stacks.");
        }
        // Update just this stack so simultaneous requests cannot overwrite other subscriptions.
        var query = org.springframework.data.mongodb.core.query.Query.query(
                org.springframework.data.mongodb.core.query.Criteria.where("id").is(account.id()));
        var update = new org.springframework.data.mongodb.core.query.Update();
        if (subscribed) update.addToSet("subscriptions", stack);
        else update.pull("subscriptions", stack);
        mongo.updateFirst(query, update, Account.class);
        return current(email);
    }

    public void removeStackSubscription(String email, String stackId) {
        var account = current(email);
        mongo.updateFirst(org.springframework.data.mongodb.core.query.Query.query(
                org.springframework.data.mongodb.core.query.Criteria.where("id").is(account.id())),
                new org.springframework.data.mongodb.core.query.Update().pull("subscriptions", stackId), Account.class);
    }

    public Account updatePhone(String email, String phoneNumber) {
        String phone = normalizePhone(phoneNumber);
        Account account = current(email);
        // Change only the phone field to preserve concurrent payment and subscription updates.
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
