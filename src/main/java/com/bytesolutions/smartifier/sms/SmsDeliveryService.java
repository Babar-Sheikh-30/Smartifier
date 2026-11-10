package com.bytesolutions.smartifier.sms;

import java.util.Comparator;
import java.util.Optional;

import com.bytesolutions.smartifier.account.Account;
import com.bytesolutions.smartifier.content.ContentStack.Item;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SmsDeliveryService {

    private final SmsContentService contentService;
    private final SmsPreferenceRepository preferences;
    private final SmsMessageFormatter formatter;
    private final SmsDeliveryClaimService claims;

    // Main constructor used by Spring.
    @Autowired
    public SmsDeliveryService(
            SmsContentService contentService,
            SmsPreferenceRepository preferences,
            SmsDeliveryClaimService claims) {

        this.contentService = contentService;
        this.preferences = preferences;
        this.formatter = new SmsMessageFormatter();
        this.claims = claims;
    }

    // Preserve compatibility with existing preparation-only unit tests.
    public SmsDeliveryService(
            SmsContentService contentService,
            SmsPreferenceRepository preferences) {

        this(contentService, preferences, null);
    }

    /**
     * Prepares an SMS message for an eligible member.
     *
     * This method does not send SMS messages.
     */
    public Optional<String> prepareMessage(Account account) {

        // Only registered members are eligible.
        if (account == null || account.role() != Account.Role.MEMBER) {
            return Optional.empty();
        }

        // A valid registered mobile number is required.
        if (account.phoneNumber() == null
                || account.phoneNumber().isBlank()) {
            return Optional.empty();
        }

        // SMS requires an explicit saved opt-in.
        boolean optedIn = preferences.findById(account.id())
                .map(preference -> preference.optedIn())
                .orElse(false);

        if (!optedIn) {
            return Optional.empty();
        }

        // Use only eligible content from subscribed stacks.
        var items = contentService.eligibleItems(account);

        if (items.isEmpty()) {
            return Optional.empty();
        }

        // Prepare the first eligible item for now.
        // This existing method does not reserve or send it.
        Item selectedItem = items.get(0);

        return Optional.of(formatter.format(selectedItem));
    }

    /**
     * Describes a successfully reserved SMS.
     *
     * A reservation is not proof that an SMS was sent.
     */
    public record ReservedMessage(
            String accountId,
            String contentKey,
            String phoneNumber,
            String message
    ) {}

    /**
     * Reserves the next eligible message in MongoDB.
     *
     * Skips content that has already been reserved.
     * Does not contact the SMS provider or send an SMS.
     */
    public Optional<ReservedMessage> reserveNextMessage(Account account) {

        if (claims == null) {
            throw new IllegalStateException(
                    "SMS delivery reservations are not configured.");
        }

        if (account == null || account.role() != Account.Role.MEMBER) {
            return Optional.empty();
        }

        if (account.phoneNumber() == null
                || account.phoneNumber().isBlank()) {
            return Optional.empty();
        }

        boolean optedIn = preferences.findById(account.id())
                .map(preference -> preference.optedIn())
                .orElse(false);

        if (!optedIn) {
            return Optional.empty();
        }

        // Retrieve only eligible published content.
        // Sort by content key for predictable selection.
        var content = contentService.eligibleContent(account).stream()
                .sorted(Comparator.comparing(
                        SmsContentService.EligibleContent::contentKey))
                .toList();

        for (var candidate : content) {

            String contentKey = candidate.contentKey();
            String message = formatter.format(candidate.item());

            // Atomically reserve the member/content combination.
            boolean claimed = claims.claim(
                    account.id(),
                    contentKey,
                    account.phoneNumber(),
                    message
            );

            if (claimed) {
                return Optional.of(new ReservedMessage(
                        account.id(),
                        contentKey,
                        account.phoneNumber(),
                        message
                ));
            }

            // Already reserved: try the next eligible item.
        }

        return Optional.empty();
    }
}
