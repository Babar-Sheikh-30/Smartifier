package com.bytesolutions.smartifier.payment.unit;

import com.bytesolutions.smartifier.payment.StripeWebhookService;

import com.bytesolutions.smartifier.payment.MembershipPricingService;
import com.bytesolutions.smartifier.payment.SubscriptionAccessService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StripeWebhookServiceTest {
    private final SubscriptionAccessService access = mock(SubscriptionAccessService.class);
    private final MembershipPricingService pricing = mock(MembershipPricingService.class);
    private final StripeWebhookService service = new StripeWebhookService(access, pricing, new ObjectMapper(), "whsec_test");
    @BeforeEach void configure() { when(pricing.accepts("price_premium")).thenReturn(true); }

    private byte[] event(String type, String status, String price) {
        return ("""
                {"type":"%s","data":{"object":{"id":"cs_purchase","mode":"subscription","subscription":"sub_member","payment_status":"%s",
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
    void paidCheckoutAndRetriesReconcileTheSameSubscription() throws Exception {
        for (String type : new String[] {"checkout.session.completed", "checkout.session.async_payment_succeeded"}) {
            var body = event(type, "paid", "price_premium");
            var signature = sign(body, Instant.now().getEpochSecond());
            service.receive(body, signature);
            service.receive(body, signature);
        }
        verify(access, times(4)).synchronize("sub_member");
    }

    @Test
    void ignoresUnpaidUnrelatedAndWrongProductEvents() throws Exception {
        for (byte[] body : new byte[][] {
                event("checkout.session.completed", "unpaid", "price_premium"),
                event("payment_intent.succeeded", "paid", "price_premium"),
                event("checkout.session.completed", "paid", "price_other")}) {
            service.receive(body, sign(body, Instant.now().getEpochSecond()));
        }
        verifyNoInteractions(access);
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
        verifyNoInteractions(access);
    }

    @Test
    void acceptsRotatedSignaturesAndOriginalUtf8Payload() throws Exception {
        var body = ("""
                {"type":"checkout.session.completed","description":"Premium — café ☕",
                "data":{"object":{"id":"cs_purchase","mode":"subscription","subscription":"sub_member","payment_status":"paid","client_reference_id":"john@abc.com",
                "metadata":{"smartifier_price_id":"price_premium"}}}}
                """).getBytes(StandardCharsets.UTF_8);
        var signature = sign(body, Instant.now().getEpochSecond())
                .replace(",v1=", ", v1=invalid, v1=");

        service.receive(body, signature);

        verify(access).synchronize("sub_member");
    }

    @Test
    void rejectsSignedInvalidJsonWithoutUpdatingAccount() throws Exception {
        var body = "{invalid".getBytes(StandardCharsets.UTF_8);
        var signature = sign(body, Instant.now().getEpochSecond());

        var error = assertThrows(ResponseStatusException.class,
                () -> service.receive(body, signature));

        assertEquals(400, error.getStatusCode().value());
        assertEquals("Invalid webhook payload.", error.getReason());
        verifyNoInteractions(access);
    }

    @Test
    void missingConfigurationFailsClosedAndUnknownMemberRemainsRetryable() throws Exception {
        var body = event("checkout.session.completed", "paid", "price_premium");
        assertEquals(503, assertThrows(ResponseStatusException.class,
                () -> new StripeWebhookService(access, pricing, new ObjectMapper(), "")
                        .receive(body, null)).getStatusCode().value());
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Checkout member was not found."))
                .when(access).synchronize("sub_member");
        var signature = sign(body, Instant.now().getEpochSecond());
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> service.receive(body, signature)).getStatusCode().value());
    }
}
