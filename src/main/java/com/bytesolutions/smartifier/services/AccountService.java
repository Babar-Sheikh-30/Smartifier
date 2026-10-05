package com.bytesolutions.smartifier.services;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
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

    public AccountService(AccountRepository accounts, PasswordEncoder passwords) {
        this.accounts = accounts;
        this.passwords = passwords;
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

    public Account register(String name, String email, String password) {
        validatePassword(password);
        try {
            // The database's unique email index also prevents concurrent duplicate registrations.
            return accounts.insert(new Account(null, name.strip(), normalizeEmail(email),
                    passwords.encode(password), Role.MEMBER, Instant.now()));
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "An account with this email already exists.");
        }
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
