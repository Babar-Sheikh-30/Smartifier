
package com.bytesolutions.smartifier.services;

import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class TwilioSmsSender implements SmsSender {

    private final boolean enabled;
    private final String accountSid;
    private final String authToken;
    private final String fromNumber;

    public TwilioSmsSender(
            @Value("${smartifier.sms.enabled:false}") boolean enabled,
            @Value("${TWILIO_ACCOUNT_SID:}") String accountSid,
            @Value("${TWILIO_AUTH_TOKEN:}") String authToken,
            @Value("${TWILIO_PHONE_NUMBER:}") String fromNumber) {

        this.enabled = enabled;
        this.accountSid = accountSid;
        this.authToken = authToken;
        this.fromNumber = fromNumber;
    }

    @Override
    public void send(String phoneNumber, String message) {

        if (!enabled) {
            throw new IllegalStateException(
                    "SMS sending is disabled.");
        }

        if (accountSid.isBlank() || authToken.isBlank()
                || fromNumber.isBlank()) {
            throw new IllegalStateException(
                    "Twilio SMS configuration is incomplete.");
        }

        if (phoneNumber == null || phoneNumber.isBlank()
                || message == null || message.isBlank()) {
            throw new IllegalArgumentException(
                    "A phone number and message are required.");
        }

        Twilio.init(accountSid, authToken);

        Message.creator(
                new PhoneNumber(phoneNumber),
                new PhoneNumber(fromNumber),
                message
        ).create();
    }
}
