package com.bytesolutions.smartifier.sms.scheduling;

import com.bytesolutions.smartifier.sms.services.SmsDispatchService;

import com.bytesolutions.smartifier.account.models.Account;
import com.bytesolutions.smartifier.account.repositories.AccountRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
public class SmsScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(SmsScheduler.class);

    private final AccountRepository accounts;
    private final SmsDispatchService dispatchService;
    private final boolean enabled;

    public SmsScheduler(
            AccountRepository accounts,
            SmsDispatchService dispatchService,
            @Value("${smartifier.sms.scheduler-enabled:false}")
            boolean enabled) {

        this.accounts = accounts;
        this.dispatchService = dispatchService;
        this.enabled = enabled;
    }

    /**
     * Scheduled SMS processing.
     *
     * Disabled by default.
     * Runs once per day at the configured time.
     */
    @Scheduled(
            cron = "${smartifier.sms.cron:0 0 10 * * *}",
            zone = "${smartifier.sms.time-zone:America/Toronto}"
    )
    public void sendScheduledMessages() {

        if (!enabled) {
            return;
        }

        log.info("Starting scheduled Smartifier SMS processing.");

        int submitted = 0;

        for (Account account : accounts.findAll()) {

            if (account.role() != Account.Role.MEMBER) {
                continue;
            }

            try {
                if (dispatchService.dispatchOne(account.id())) {
                    submitted++;
                }
            } catch (Exception exception) {
                log.error(
                        "SMS processing failed for account {}",
                        account.id(),
                        exception
                );
            }
        }

        log.info(
                "Scheduled SMS processing completed. Submitted: {}",
                submitted
        );
    }
}
