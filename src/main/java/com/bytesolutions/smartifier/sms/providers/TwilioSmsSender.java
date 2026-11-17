package com.bytesolutions.smartifier.sms.providers;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

@Service
@ConditionalOnProperty(
        name = "smartifier.sms.provider",
        havingValue = "twilio"
)
public class TwilioSmsSender implements SmsSender {

    private final boolean enabled;
    private final String accountSid;
    private final String authToken;
    private final String fromNumber;
    private final RestClient client;

    public TwilioSmsSender(
            @Value("${smartifier.sms.enabled:false}") boolean enabled,
            @Value("${TWILIO_ACCOUNT_SID:}") String accountSid,
            @Value("${TWILIO_AUTH_TOKEN:}") String authToken,
            @Value("${TWILIO_FROM_NUMBER:}") String fromNumber) {

        this.enabled = enabled;
        this.accountSid = accountSid;
        this.authToken = authToken;
        this.fromNumber = fromNumber;
        this.client = RestClient.create("https://api.twilio.com");
    }

    @Override
    public void send(String phoneNumber, String message) {

        if (!enabled) {
            throw new IllegalStateException("SMS sending is disabled.");
        }

        if (accountSid.isBlank()
                || authToken.isBlank()
                || fromNumber.isBlank()) {
            throw new IllegalStateException(
                    "Twilio credentials are not configured.");
        }

        if (phoneNumber == null || phoneNumber.isBlank()
                || message == null || message.isBlank()) {
            throw new IllegalArgumentException(
                    "A phone number and message are required.");
        }

        var form = new LinkedMultiValueMap<String, String>();
        form.add("To", phoneNumber);
        form.add("From", fromNumber);
        form.add("Body", "sms_account_alerts");

        Map<?, ?> response = client.post()
                .uri("/2010-04-01/Accounts/{accountSid}/Messages.json",
                        accountSid)
                .headers(headers ->
                        headers.setBasicAuth(accountSid, authToken))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);

        if (response == null || response.get("sid") == null
                || response.get("error_code") != null) {
            throw new IllegalStateException(
                    "Twilio SMS request was not accepted.");
        }
    }
}
