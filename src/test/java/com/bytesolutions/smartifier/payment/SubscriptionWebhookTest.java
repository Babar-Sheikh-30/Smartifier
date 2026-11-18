package com.bytesolutions.smartifier.payment;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SubscriptionWebhookTest {
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final SubscriptionAccessService access = mock(SubscriptionAccessService.class);
    private StripeWebhookService webhook() {
        var pricing = mock(MembershipPricingService.class);
        when(pricing.accepts("price_premium")).thenReturn(true);
        return new StripeWebhookService(access, pricing, new ObjectMapper(), "whsec_test");
    }
    static void deliver(StripeWebhookService webhook, String type, String object) throws Exception {
        byte[] body = ("{\"type\":\"" + type + "\",\"data\":{\"object\":" + object + "}}")
                .getBytes(StandardCharsets.UTF_8);
        long timestamp = Instant.now().getEpochSecond();
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("whsec_test".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
        webhook.receive(body, "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(mac.doFinal(body)));
    }
    @Test void currentAndLegacyInvoiceEventsTriggerReconciliationInsteadOfTrustingPaymentSnapshot() throws Exception {
        var webhook = webhook();
        deliver(webhook, "invoice.payment_failed", "{\"parent\":{\"subscription_details\":{\"subscription\":\"sub_member\"}}}");
        deliver(webhook, "invoice.paid", "{\"subscription\":\"sub_member\"}");
        deliver(webhook, "invoice.payment_succeeded", "{\"subscription\":{\"id\":\"sub_member\"}}");
        verify(access, times(3)).synchronize("sub_member");
        verifyNoInteractions(mongo);
    }
    @Test void canceledPausedAndResumedEventsReconcileBySubscriptionId() throws Exception {
        var webhook = webhook();
        for (String type : new String[] {"customer.subscription.created", "customer.subscription.updated",
                "customer.subscription.deleted", "customer.subscription.paused", "customer.subscription.resumed"}) {
            deliver(webhook, type, "{\"id\":\"sub_member\"}");
        }
        verify(access, times(5)).synchronize("sub_member");
        verifyNoInteractions(mongo);
    }
    @Test void paidSubscriptionCheckoutDoesNotBlindlyReactivatePremiumAfterCancellation() throws Exception {
        deliver(webhook(), "checkout.session.completed", """
            {"id":"cs_subscription","mode":"subscription","payment_status":"paid","subscription":"sub_member",
             "client_reference_id":"member@example.com","metadata":{"smartifier_price_id":"price_premium"}}
            """);
        verify(access).synchronize("sub_member");
        verifyNoInteractions(mongo);
    }
    @Test void paidOneTimeCheckoutIsIgnored() throws Exception {
        deliver(webhook(), "checkout.session.completed", """
            {"id":"cs_purchase","mode":"payment","payment_status":"paid",
             "client_reference_id":"member@example.com","metadata":{"smartifier_price_id":"price_premium"}}
            """);
        verifyNoInteractions(access);
    }
    @Test void invalidSignaturesNeverReachTheSubscriptionHandler() {
        byte[] body = "{\"type\":\"customer.subscription.deleted\",\"data\":{\"object\":{\"id\":\"sub_member\"}}}"
                .getBytes(StandardCharsets.UTF_8);
        assertThrows(ResponseStatusException.class, () -> webhook().receive(body, "t=1,v1=forged"));
        verifyNoInteractions(access, mongo);
    }
    @Test void unrelatedInvoicesAreIgnoredAndFailuresRemainRetryable() throws Exception {
        var webhook = webhook();
        deliver(webhook, "invoice.paid", "{\"id\":\"in_one_time\"}");
        verifyNoInteractions(access);
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE))
                .when(access).synchronize("sub_member");
        assertThrows(ResponseStatusException.class,
                () -> deliver(webhook, "customer.subscription.updated", "{\"id\":\"sub_member\"}"));
    }
}
