package com.bytesolutions.smartifier.sms.services;

import com.bytesolutions.smartifier.sms.providers.SmsSender;

import com.bytesolutions.smartifier.sms.models.SmsDailyClaim;
import com.bytesolutions.smartifier.sms.models.SmsDelivery;
import com.bytesolutions.smartifier.sms.repositories.SmsPreferenceRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

import com.bytesolutions.smartifier.account.models.Account;
import com.bytesolutions.smartifier.account.repositories.AccountRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

@Service
public class SmsDispatchService {

    private static final Logger log =
            LoggerFactory.getLogger(SmsDispatchService.class);

    private static final ZoneId SMS_TIME_ZONE =
            ZoneId.of("America/Toronto");

    private final AccountRepository accounts;
    private final SmsPreferenceRepository preferences;
    private final SmsDeliveryService deliveryService;
    private final SmsContentService contentService;
    private final SmsSender sender;
    private final MongoTemplate mongo;
    private final boolean enabled;

    public SmsDispatchService(
            AccountRepository accounts,
            SmsPreferenceRepository preferences,
            SmsDeliveryService deliveryService,
            SmsContentService contentService,
            SmsSender sender,
            MongoTemplate mongo,
            @Value("${smartifier.sms.enabled:false}") boolean enabled) {

        this.accounts = accounts;
        this.preferences = preferences;
        this.deliveryService = deliveryService;
        this.contentService = contentService;
        this.sender = sender;
        this.mongo = mongo;
        this.enabled = enabled;
    }

    /**
     * Processes one eligible SMS for a member.
     *
     * No SMS is sent when sending is disabled.
     * Members are limited to one SMS attempt per Toronto calendar day.
     * Existing content reservations prevent duplicate content claims.
     */
    public boolean dispatchOne(String accountId) {

        // Never create reservations or send messages when disabled.
        if (!enabled) {
            return false;
        }

        if (accountId == null || accountId.isBlank()) {
            return false;
        }

        Account account = accounts.findById(accountId).orElse(null);

        if (!eligible(account)) {
            return false;
        }

        // Do not consume a daily allowance if there is no eligible content.
        if (contentService.eligibleContent(account).isEmpty()) {
            return false;
        }

        // Atomically reserve today's allowance for this member.
        // A duplicate claim means another process has already reserved it.
        if (!claimDailyAllowance(accountId)) {
            return false;
        }

        // Atomically claim the next available content item.
        var reservation = deliveryService.reserveNextMessage(account);

        if (reservation.isEmpty()) {
            return false;
        }

        var reserved = reservation.get();

        // Refresh the account and consent immediately before sending.
        Account current = accounts.findById(accountId).orElse(null);

        if (!eligible(current)
                || !Objects.equals(
                        reserved.phoneNumber(),
                        current.phoneNumber())) {

            updateStatus(reserved, SmsDelivery.Status.FAILED);
            return false;
        }

        // Ensure the reserved content is still eligible.
        boolean contentStillEligible =
                contentService.eligibleContent(current).stream()
                        .anyMatch(content ->
                                content.contentKey()
                                        .equals(reserved.contentKey()));

        if (!contentStillEligible) {
            updateStatus(reserved, SmsDelivery.Status.FAILED);
            return false;
        }

        try {
            sender.send(reserved.phoneNumber(), reserved.message());

            // SENT means submitted to the SMS provider.
            // It does not prove handset delivery.
            updateStatus(reserved, SmsDelivery.Status.SENT);

            return true;

        } catch (RuntimeException exception) {

            log.error(
                    "SMS dispatch failed for account {}: {}",
                    accountId,
                    exception.getMessage()
            );

            // Do not automatically retry: the provider may have
            // accepted the message before an error was returned.
            updateStatus(reserved, SmsDelivery.Status.FAILED);

            return false;
        }
    }

    /**
     * Reserves one daily SMS allowance for an account.
     *
     * MongoDB's unique _id prevents simultaneous processes
     * from claiming the same member/date combination.
     */
    private boolean claimDailyAllowance(String accountId) {

        LocalDate today = LocalDate.now(SMS_TIME_ZONE);

        String claimId = accountId + ":" + today;

        SmsDailyClaim claim = new SmsDailyClaim(
                claimId,
                accountId,
                today,
                Instant.now()
        );

        try {
            mongo.insert(claim);
            return true;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    private boolean eligible(Account account) {

        if (account == null
                || account.role() != Account.Role.MEMBER
                || account.phoneNumber() == null
                || account.phoneNumber().isBlank()) {
            return false;
        }

        return preferences.findById(account.id())
                .map(preference -> preference.optedIn())
                .orElse(false);
    }

    private void updateStatus(
            SmsDeliveryService.ReservedMessage reserved,
            SmsDelivery.Status status) {

        Query query = Query.query(
                Criteria.where("accountId")
                        .is(reserved.accountId())
                        .and("contentKey")
                        .is(reserved.contentKey())
                        .and("status")
                        .is(SmsDelivery.Status.PENDING)
        );

        Update update = new Update()
                .set("status", status)
                .set("updatedAt", Instant.now());

        mongo.updateFirst(query, update, SmsDelivery.class);
    }
}

