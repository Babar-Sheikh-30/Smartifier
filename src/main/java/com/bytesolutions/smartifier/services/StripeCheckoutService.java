package com.bytesolutions.smartifier.services;

import java.net.URI;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StripeCheckoutService {
    private final RestClient client;
    private final String secretKey;
    private final String priceId;
    private final String mode;
    private final String baseUrl;

    public StripeCheckoutService(RestClient.Builder builder,
            @Value("${smartifier.stripe.secret-key}") String secretKey,
            @Value("${smartifier.stripe.price-id}") String priceId,
            @Value("${smartifier.stripe.mode}") String mode,
            @Value("${smartifier.public-url}") String baseUrl) {
        this.client = builder.baseUrl("https://api.stripe.com").build();
        this.secretKey = secretKey;
        this.priceId = priceId;
        this.mode = mode;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    public record Checkout(String url) {}

    public Checkout create(String email) {
        if (secretKey.isBlank() || priceId.isBlank() || !Set.of("payment", "subscription").contains(mode)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Payments are not configured yet. Please try again later.");
        }
        var form = new LinkedMultiValueMap<String, String>();
        form.add("mode", mode);
        form.add("line_items[0][price]", priceId);
        form.add("line_items[0][quantity]", "1");
        form.add("customer_email", email);
        // Server-selected account and price values let the webhook identify the paid membership.
        form.add("client_reference_id", email);
        form.add("metadata[smartifier_price_id]", priceId);
        // Returning to this URL is only a UI notice; the verified webhook grants premium.
        form.add("success_url", baseUrl + "/member/dashboard?checkout=returned");
        form.add("cancel_url", baseUrl + "/member/dashboard?checkout=cancelled");
        try {
            var checkout = client.post().uri("/v1/checkout/sessions")
                    .headers(headers -> headers.setBearerAuth(secretKey))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(Checkout.class);
            if (checkout != null && checkout.url() != null) {
                var url = URI.create(checkout.url());
                // Only redirect members to Stripe's hosted checkout over HTTPS.
                if ("https".equals(url.getScheme()) && "checkout.stripe.com".equals(url.getHost())
                        && url.getRawUserInfo() == null && url.getPort() == -1) {
                    return checkout;
                }
            }
        } catch (RestClientException | IllegalArgumentException exception) {
            // Stripe responses can contain sensitive configuration details; do not expose them.
        }
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Could not open Stripe checkout. Please try again.");
    }
}
