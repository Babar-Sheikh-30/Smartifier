package com.bytesolutions.smartifier.payment;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.param.checkout.SessionCreateParams;
import java.net.URI;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StripeCheckoutService {
    private final StripeClient client;
    private final String secretKey;
    private final String priceId;
    private final String mode;
    private final String baseUrl;

    public StripeCheckoutService(@Lazy StripeClient client,
            @Value("${smartifier.stripe.secret-key}") String secretKey,
            @Value("${smartifier.stripe.price-id}") String priceId,
            @Value("${smartifier.stripe.mode}") String mode,
            @Value("${smartifier.public-url}") String baseUrl) {
        this.client = client;
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
        var params = SessionCreateParams.builder()
                .setMode(mode.equals("payment") ? SessionCreateParams.Mode.PAYMENT : SessionCreateParams.Mode.SUBSCRIPTION)
                .addLineItem(SessionCreateParams.LineItem.builder().setPrice(priceId).setQuantity(1L).build())
                // Server-selected account and price values let the webhook identify the paid membership.
                .setCustomerEmail(email).setClientReferenceId(email)
                .putMetadata("smartifier_price_id", priceId)
                // Returning to this URL is only a UI notice; the verified webhook grants premium.
                .setSuccessUrl(baseUrl + "/member/dashboard?checkout=returned")
                .setCancelUrl(baseUrl + "/member/dashboard?checkout=cancelled")
                .build();
        try {
            var session = client.v1().checkout().sessions().create(params);
            if (session != null && session.getUrl() != null) {
                var url = URI.create(session.getUrl());
                // Only redirect members to Stripe's hosted checkout over HTTPS.
                if ("https".equals(url.getScheme()) && "checkout.stripe.com".equals(url.getHost())
                        && url.getRawUserInfo() == null && url.getPort() == -1) {
                    return new Checkout(session.getUrl());
                }
            }
        } catch (StripeException | IllegalArgumentException exception) {
            // Stripe responses can contain sensitive configuration details; do not expose them.
        }
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Could not open Stripe checkout. Please try again.");
    }
}
