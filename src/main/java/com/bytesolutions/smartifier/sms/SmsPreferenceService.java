package com.bytesolutions.smartifier.sms;

import com.bytesolutions.smartifier.account.AccountService;

import java.time.Instant;

import com.bytesolutions.smartifier.account.Account;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SmsPreferenceService {

    private final SmsPreferenceRepository preferences;
    private final AccountService accounts;

    public SmsPreferenceService(
            SmsPreferenceRepository preferences,
            AccountService accounts) {
        this.preferences = preferences;
        this.accounts = accounts;
    }

    public boolean isOptedIn(String email) {
        Account account = accounts.current(email);

        return preferences.findById(account.id())
                .map(SmsPreference::optedIn)
                .orElse(false);
    }

    public SmsPreference updatePreference(String email, boolean optedIn) {
        Account account = accounts.current(email);

        if (account.role() != Account.Role.MEMBER) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Only members can manage SMS preferences.");
        }

        Instant now = Instant.now();

        SmsPreference existing = preferences.findById(account.id())
                .orElse(null);

        Instant consentedAt = optedIn
                ? (existing != null && existing.optedIn()
                        ? existing.consentedAt() : now)
                : (existing == null ? null : existing.consentedAt());

        SmsPreference updated = new SmsPreference(
                account.id(),
                optedIn,
                consentedAt,
                now
        );

        return preferences.save(updated);
    }

    /**
     * Updates a member's phone number and revokes SMS consent
     * whenever the actual number changes.
     */
    public Account updatePhoneAndResetConsent(String email, String phoneNumber) {
        Account account = accounts.current(email);
        String normalizedPhone = AccountService.normalizePhone(phoneNumber);

        if (!normalizedPhone.equals(account.phoneNumber())) {
            // Revoke consent before updating the number so existing
            // consent cannot authorize delivery to a new phone.
            updatePreference(email, false);
        }

        return accounts.updatePhone(email, normalizedPhone);
    }

}
