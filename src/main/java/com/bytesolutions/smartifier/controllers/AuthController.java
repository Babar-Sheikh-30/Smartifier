package com.bytesolutions.smartifier.controllers;

import java.security.Principal;
import java.time.Instant;
import java.util.Map;
import com.bytesolutions.smartifier.models.Account;
import com.bytesolutions.smartifier.services.AccountService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AccountService accounts;

    public AuthController(AccountService accounts) { this.accounts = accounts; }

    public record Registration(@NotBlank @Size(max = 100) String name,
                               @NotBlank @Email @Size(max = 254) String email,
                               @NotBlank @Size(min = 12, max = 64) String password,
                               @NotBlank String passwordConfirmation) {}

    public record Profile(String id, String name, String email, Account.Role role, Instant createdAt) {
        public static Profile from(Account account) {
            return new Profile(account.id(), account.name(), account.email(), account.role(), account.createdAt());
        }
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Profile register(@Valid @RequestBody Registration request) {
        if (!request.password().equals(request.passwordConfirmation())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Passwords do not match.");
        }
        return Profile.from(accounts.register(request.name(), request.email(), request.password()));
    }

    @GetMapping("/me")
    public Profile me(Principal principal) { return Profile.from(accounts.current(principal.getName())); }
}
