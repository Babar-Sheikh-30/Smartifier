package com.bytesolutions.smartifier.payment;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.net.Webhook;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** Verifies signatures before dispatching payment and subscription access changes. */
@Service
public class StripeWebhookService {
    private final MembershipPricingService pricing;
    private final SubscriptionAccessService access;
    private static final Set<String> SUBSCRIPTION_EVENTS = Set.of("customer.subscription.created",
            "customer.subscription.updated", "customer.subscription.deleted", "customer.subscription.paused",
            "customer.subscription.resumed");
    private static final Set<String> INVOICE_EVENTS = Set.of("invoice.paid", "invoice.payment_succeeded",
            "invoice.payment_failed", "invoice.payment_action_required", "invoice.marked_uncollectible", "invoice.voided");
    private final ObjectMapper mapper;
    private final String secret;

    public StripeWebhookService(SubscriptionAccessService access, MembershipPricingService pricing, ObjectMapper mapper,
            @Value("${smartifier.stripe.webhook-secret:}") String secret) {
        this.access = access;
        this.pricing = pricing;
        this.mapper = mapper;
        this.secret = secret;
    }

    public void receive(byte[] body, String signature) {
        if (secret.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Webhook is not configured.");
        }
        // Verify the original bytes before parsing; reserialized JSON can change the signature.
        verify(body, signature);
        final tools.jackson.databind.JsonNode event;
        try {
            event = mapper.readTree(body);
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid webhook payload.");
        }
        String type = event.path("type").asText();
        var object = event.path("data").path("object");
        if (SUBSCRIPTION_EVENTS.contains(type)) {
            access.synchronize(object.path("id").asText());
            return;
        }
        if (INVOICE_EVENTS.contains(type)) {
            // Current Stripe versions nest the subscription under parent.subscription_details.
            String id = stripeId(object.path("parent").path("subscription_details").path("subscription"));
            if (id.isBlank()) id = stripeId(object.path("subscription")); // Older webhook API versions.
            if (!id.isBlank()) access.synchronize(id);
            return;
        }
        if (!type.equals("checkout.session.completed")
                && !type.equals("checkout.session.async_payment_succeeded")) return;
        var session = event.path("data").path("object");
        // Delayed payment methods may complete checkout before payment succeeds.
        String paidPrice = session.path("metadata").path("smartifier_price_id").asText();
        if (!session.path("payment_status").asText().equals("paid")
                || !pricing.accepts(paidPrice)) return;
        String email = session.path("client_reference_id").asText();
        if (email.isBlank()) return;
        String subscription = stripeId(session.path("subscription"));
        if (!subscription.isBlank()) access.synchronize(subscription);
    }

    private static String stripeId(tools.jackson.databind.JsonNode value) {
        return value.isString() ? value.asText() : value.path("id").asText();
    }

    private void verify(byte[] body, String signature) {
        try {
            if (signature == null) throw new IllegalArgumentException();
            String header = String.join(",", Arrays.stream(signature.split(","))
                    .map(String::trim).toList());
            // The SDK verifies the signature and rejects timestamps older than five minutes.
            Webhook.Signature.verifyHeader(new String(body, StandardCharsets.UTF_8), header,
                    secret, Webhook.DEFAULT_TOLERANCE);
            // Preserve our future-timestamp check; the SDK only enforces the oldest allowed time.
            long sent = Arrays.stream(header.split(",")).filter(part -> part.startsWith("t="))
                    .mapToLong(part -> Long.parseLong(part.substring(2))).findFirst().orElseThrow();
            if (sent > Instant.now().getEpochSecond() + Webhook.DEFAULT_TOLERANCE) {
                throw new IllegalArgumentException();
            }
        } catch (SignatureVerificationException | RuntimeException exception) {
            // Never expose provider exception details, signatures, or payment payloads.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid webhook signature.");
        }
    }
}
