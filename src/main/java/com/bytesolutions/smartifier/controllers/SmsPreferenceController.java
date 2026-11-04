
package com.bytesolutions.smartifier.controllers;

import java.security.Principal;

import com.bytesolutions.smartifier.services.SmsPreferenceService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/member/sms")
public class SmsPreferenceController {

    private final SmsPreferenceService preferences;

    public SmsPreferenceController(SmsPreferenceService preferences) {
        this.preferences = preferences;
    }

    public record SmsSettings(boolean optedIn) {}

    public record UpdateSmsSettings(@NotNull Boolean optedIn) {}

    @GetMapping
    public SmsSettings getSettings(Principal principal) {
        return new SmsSettings(
                preferences.isOptedIn(principal.getName())
        );
    }

    @PostMapping
    public SmsSettings updateSettings(
            Principal principal,
            @Valid @RequestBody UpdateSmsSettings request) {

        var updated = preferences.updatePreference(
                principal.getName(),
                request.optedIn()
        );

        return new SmsSettings(updated.optedIn());
    }
}
