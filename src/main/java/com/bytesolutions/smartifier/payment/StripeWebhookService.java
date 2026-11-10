package com.bytesolutions.smartifier.payment;

import com.bytesolutions.smartifier.account.Account;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.net.Webhook;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** Verifies Stripe webhook signatures and activates premium access for paid checkouts. */
@Service
public class StripeWebhookService {
    private final MongoTemplate mongo;
    private final ObjectMapper mapper;
    private final String secret;
    private final String priceId;

    public StripeWebhookService(MongoTemplate mongo, ObjectMapper mapper,
            @Value("${smartifier.stripe.webhook-secret:}") String secret,
            @Value("${smartifier.stripe.price-id:}") String priceId) {
        this.mongo = mongo;
        this.mapper = mapper;
        this.secret = secret;
        this.priceId = priceId;
    }

    public void receive(byte[] body, String signature) {
        if (secret.isBlank() || priceId.isBlank()) {
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
        if (!type.equals("checkout.session.completed")
                && !type.equals("checkout.session.async_payment_succeeded")) return;
        var session = event.path("data").path("object");
        // Delayed payment methods may complete checkout before payment succeeds.
        if (!session.path("payment_status").asText().equals("paid")
                || !session.path("metadata").path("smartifier_price_id").asText().equals(priceId)) return;
        String email = session.path("client_reference_id").asText();
        if (email.isBlank()) return;
        // Setting only premium makes retries harmless and preserves other account fields.
        var result = mongo.updateFirst(Query.query(Criteria.where("email").is(email)
                        .and("role").is(Account.Role.MEMBER)),
                new Update().set("premium", true), Account.class);
        if (result.getMatchedCount() == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Checkout member was not found.");
        }
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
