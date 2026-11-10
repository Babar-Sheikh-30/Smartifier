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
        havingValue = "textbelt"
)
public class TextbeltSmsSender implements SmsSender {

    private final boolean enabled;
    private final String apiKey;
    private final RestClient client;

    public TextbeltSmsSender(
            @Value("${smartifier.sms.enabled:false}") boolean enabled,
            @Value("${TEXTBELT_API_KEY:}") String apiKey) {

        this.enabled = enabled;
        this.apiKey = apiKey;
        this.client = RestClient.create("https://textbelt.com");
    }

    @Override
    public void send(String phoneNumber, String message) {

        if (!enabled) {
            throw new IllegalStateException("SMS sending is disabled.");
        }

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "Textbelt API key is not configured.");
        }

        if (phoneNumber == null || phoneNumber.isBlank()
                || message == null || message.isBlank()) {
            throw new IllegalArgumentException(
                    "A phone number and message are required.");
        }

        var form = new LinkedMultiValueMap<String, String>();
        form.add("phone", phoneNumber);
        form.add("message", message);
        form.add("key", apiKey);

        Map<?, ?> response = client.post()
                .uri("/text")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);

        if (response == null
                || !Boolean.TRUE.equals(response.get("success"))) {

            String error = response == null
                    ? "No response from Textbelt"
                    : String.valueOf(response.get("error"));

            throw new IllegalStateException(
                    "Textbelt SMS request failed: " + error);
        }
    }
}