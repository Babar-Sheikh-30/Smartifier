
package com.bytesolutions.smartifier.controllers;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.bytesolutions.smartifier.models.Account;
import com.bytesolutions.smartifier.services.SmsPreferenceService;
import com.twilio.security.RequestValidator;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/sms/twilio")
public class TwilioWebhookController {

    private final MongoTemplate mongo;
    private final SmsPreferenceService preferences;
    private final String authToken;
    private final String accountSid;
    private final String fromNumber;
    private final String webhookUrl;

    public TwilioWebhookController(
            MongoTemplate mongo,
            SmsPreferenceService preferences,
            @Value("${TWILIO_AUTH_TOKEN:}") String authToken,
            @Value("${TWILIO_ACCOUNT_SID:}") String accountSid,
            @Value("${TWILIO_PHONE_NUMBER:}") String fromNumber,
            @Value("${smartifier.sms.webhook-url:}") String webhookUrl) {

        this.mongo = mongo;
        this.preferences = preferences;
        this.authToken = authToken;
        this.accountSid = accountSid;
        this.fromNumber = fromNumber;
        this.webhookUrl = webhookUrl;
    }

    /**
     * Receives form-encoded inbound messages from Twilio.
     * Only signed requests are accepted.
     */
    @PostMapping(
            value = "/webhook",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_XML_VALUE
    )
    public String receive(
            @RequestParam MultiValueMap<String, String> form,
            @RequestHeader(
                    value = "X-Twilio-Signature",
                    required = false
            ) String signature) {

        // Fail closed if webhook verification is not configured.
        if (authToken.isBlank() || accountSid.isBlank()
                || fromNumber.isBlank() || webhookUrl.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Twilio webhook is not configured.");
        }

        if (signature == null || signature.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Invalid Twilio signature.");
        }

        Map<String, String> parameters = form.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().getFirst()
                ));

        RequestValidator validator =
                new RequestValidator(authToken);

        if (!validator.validate(webhookUrl, parameters, signature)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Invalid Twilio signature.");
        }

        // Verify that the request belongs to our Twilio account
        // and was sent to our configured SMS number.
        if (!accountSid.equals(parameters.get("AccountSid"))
                || !fromNumber.equals(parameters.get("To"))) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Unexpected Twilio message destination.");
        }

        String from = parameters.get("From");
        String body = parameters.get("Body");

        if (from == null || from.isBlank()
                || body == null || body.isBlank()) {
            return "<Response></Response>";
        }

        String keyword = body.trim().toUpperCase(java.util.Locale.ROOT);

        boolean optedOut = List.of(
                "STOP",
                "STOPALL",
                "UNSUBSCRIBE",
                "CANCEL",
                "END",
                "QUIT"
        ).contains(keyword);

        // Twilio Messaging Services may also supply OptOutType.
        if ("STOP".equalsIgnoreCase(parameters.get("OptOutType"))) {
            optedOut = true;
        }

        if (optedOut) {
            Query query = Query.query(
                    Criteria.where("phoneNumber").is(from)
            );

            // Disable preferences for all member accounts using
            // the number that sent the STOP request.
            for (Account account : mongo.find(query, Account.class)) {
                if (account.role() == Account.Role.MEMBER) {
                    preferences.updatePreference(
                            account.email(), false);
                }
            }
        }

        // Empty TwiML: no additional automated SMS response.
        return "<Response></Response>";
    }
}
