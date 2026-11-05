package com.bytesolutions.smartifier;

import com.bytesolutions.smartifier.models.Account;
import com.bytesolutions.smartifier.services.StripeWebhookService;
import com.mongodb.client.result.UpdateResult;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StripeWebhookServiceTest {
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final StripeWebhookService service = new StripeWebhookService(mongo, new ObjectMapper(),
            "whsec_test", "price_premium");

    private byte[] event(String type, String status, String price) {
        return ("""
                {"type":"%s","data":{"object":{"payment_status":"%s",
                "client_reference_id":"john@abc.com","metadata":{"smartifier_price_id":"%s"}}}}
                """).formatted(type, status, price).getBytes(StandardCharsets.UTF_8);
    }

    private String sign(byte[] body, long timestamp) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("whsec_test".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
        return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    @Test
    void paidCheckoutAndRetriesOnlySetPremiumForTheMember() throws Exception {
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(Account.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));
        for (String type : new String[] {"checkout.session.completed", "checkout.session.async_payment_succeeded"}) {
            var body = event(type, "paid", "price_premium");
            var signature = sign(body, Instant.now().getEpochSecond());
            service.receive(body, signature);
            service.receive(body, signature);
        }
        var query = ArgumentCaptor.forClass(Query.class);
        var update = ArgumentCaptor.forClass(Update.class);
        verify(mongo, times(4)).updateFirst(query.capture(), update.capture(), eq(Account.class));
        assertEquals("john@abc.com", query.getValue().getQueryObject().get("email"));
        assertEquals(Account.Role.MEMBER, query.getValue().getQueryObject().get("role"));
        assertEquals(new org.bson.Document("$set", new org.bson.Document("premium", true)),
                update.getValue().getUpdateObject());
    }

    @Test
    void ignoresUnpaidUnrelatedAndWrongProductEvents() throws Exception {
        for (byte[] body : new byte[][] {
                event("checkout.session.completed", "unpaid", "price_premium"),
                event("payment_intent.succeeded", "paid", "price_premium"),
                event("checkout.session.completed", "paid", "price_other")}) {
            service.receive(body, sign(body, Instant.now().getEpochSecond()));
        }
        verifyNoInteractions(mongo);
    }

    @Test
    void rejectsMissingForgedExpiredFutureAndTamperedSignatures() throws Exception {
        var body = event("checkout.session.completed", "paid", "price_premium");
        long now = Instant.now().getEpochSecond();
        for (String signature : new String[] {null, "", "t=invalid,v1=bad", "t=1,v1", "v1=bad",
                "t=1,v1=bad", sign(body, now - 600),
                sign(body, now + 600), sign("{}".getBytes(StandardCharsets.UTF_8), now)}) {
            assertEquals(400, assertThrows(ResponseStatusException.class,
                    () -> service.receive(body, signature)).getStatusCode().value());
        }
        verifyNoInteractions(mongo);
    }

    @Test
    void acceptsRotatedSignaturesAndOriginalUtf8Payload() throws Exception {
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(Account.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));
        var body = ("""
                {"type":"checkout.session.completed","description":"Premium — café ☕",
                "data":{"object":{"payment_status":"paid","client_reference_id":"john@abc.com",
                "metadata":{"smartifier_price_id":"price_premium"}}}}
                """).getBytes(StandardCharsets.UTF_8);
        var signature = sign(body, Instant.now().getEpochSecond())
                .replace(",v1=", ", v1=invalid, v1=");

        service.receive(body, signature);

        verify(mongo).updateFirst(any(Query.class), any(Update.class), eq(Account.class));
    }

    @Test
    void rejectsSignedInvalidJsonWithoutUpdatingAccount() throws Exception {
        var body = "{invalid".getBytes(StandardCharsets.UTF_8);
        var signature = sign(body, Instant.now().getEpochSecond());

        var error = assertThrows(ResponseStatusException.class,
                () -> service.receive(body, signature));

        assertEquals(400, error.getStatusCode().value());
        assertEquals("Invalid webhook payload.", error.getReason());
        verifyNoInteractions(mongo);
    }

    @Test
    void missingConfigurationFailsClosedAndUnknownMemberRemainsRetryable() throws Exception {
        var body = event("checkout.session.completed", "paid", "price_premium");
        assertEquals(503, assertThrows(ResponseStatusException.class,
                () -> new StripeWebhookService(mongo, new ObjectMapper(), "", "price_premium")
                        .receive(body, null)).getStatusCode().value());
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(Account.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));
        var signature = sign(body, Instant.now().getEpochSecond());
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> service.receive(body, signature)).getStatusCode().value());
    }
}
