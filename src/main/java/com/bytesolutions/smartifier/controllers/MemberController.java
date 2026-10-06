package com.bytesolutions.smartifier.controllers;

import java.security.Principal;
import com.bytesolutions.smartifier.services.AccountService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/member")
public class MemberController {
    private final AccountService accounts;
    public MemberController(AccountService accounts) { this.accounts = accounts; }

    public record Subscription(@NotBlank String stack, @NotNull Boolean subscribed) {}
    public record Phone(@NotBlank String phoneNumber) {}

    @PostMapping("/phone")
    public AuthController.Profile phone(Principal principal, @Valid @RequestBody Phone request) {
        return AuthController.Profile.from(accounts.updatePhone(principal.getName(), request.phoneNumber()));
    }

    @PostMapping("/subscriptions")
    public AuthController.Profile subscribe(Principal principal, @Valid @RequestBody Subscription request) {
        return AuthController.Profile.from(accounts.subscribe(principal.getName(), request.stack(), request.subscribed()));
    }
}
