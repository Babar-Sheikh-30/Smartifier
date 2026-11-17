package com.bytesolutions.smartifier.sms.controllers;

import com.bytesolutions.smartifier.account.services.AccountService;
import com.bytesolutions.smartifier.sms.providers.SmsSender;
import com.bytesolutions.smartifier.sms.services.SmsPreferenceService;

import java.security.Principal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/member/sms")
public class SmsPreferenceController {

    private final SmsPreferenceService preferences;
    private final AccountService accounts;
    private final SmsSender sender;
    private final boolean sendingEnabled;
    private final String trialRecipient;

    public SmsPreferenceController(SmsPreferenceService preferences) {
        this.preferences = preferences;
        this.accounts = null;
        this.sender = null;
        this.sendingEnabled = false;
        this.trialRecipient = "";
    }

    @Autowired
    public SmsPreferenceController(
            SmsPreferenceService preferences,
            ObjectProvider<AccountService> accounts,
            ObjectProvider<SmsSender> sender,
            @Value("${smartifier.sms.enabled:false}") boolean sendingEnabled,
            @Value("${TWILIO_TEST_TO:}") String trialRecipient) {

        this.preferences = preferences;
        this.accounts = accounts.getIfAvailable();
        this.sender = sender.getIfAvailable();
        this.sendingEnabled = sendingEnabled;
        this.trialRecipient = trialRecipient;
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

        String email = principal.getName();
        boolean previouslyOptedIn = preferences.isOptedIn(email);

        boolean shouldSend = sendingEnabled
                && !previouslyOptedIn
                && Boolean.TRUE.equals(request.optedIn());

        String phoneNumber = null;

        if (shouldSend) {
            if (accounts == null || sender == null) {
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "SMS delivery is unavailable.");
            }

            phoneNumber = accounts.current(email).phoneNumber();

            if (trialRecipient.isBlank()
                    || !trialRecipient.equals(phoneNumber)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "SMS trial delivery requires the verified demo phone number.");
            }
        }

        var updated = preferences.updatePreference(
                email,
                request.optedIn()
        );

        if (shouldSend && updated.optedIn()) {
            try {
                sender.send(phoneNumber, "sms_account_alerts");
            } catch (RuntimeException exception) {
                preferences.updatePreference(email, false);
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Twilio SMS request failed.");
            }
        }

        return new SmsSettings(updated.optedIn());
    }
}
