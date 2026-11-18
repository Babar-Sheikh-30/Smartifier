package com.bytesolutions.smartifier.payment;

import com.bytesolutions.smartifier.account.models.Account;
import com.bytesolutions.smartifier.payment.SubscriptionAccessService.*;
import com.mongodb.client.result.UpdateResult;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.web.server.ResponseStatusException;
import static com.bytesolutions.smartifier.payment.MembershipPricingServiceTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SubscriptionAccessServiceTest {
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final MembershipPricingService pricing = mock(MembershipPricingService.class);
    private final Map<String, Grant> grants = new LinkedHashMap<>();
    private final AtomicBoolean premium = new AtomicBoolean(true);
    private final AtomicBoolean imported = new AtomicBoolean(true);
    private static final String EMAIL = "member@example.com";

    @BeforeEach void store() {
        when(pricing.accepts("price_premium")).thenReturn(true);
        when(mongo.findById(anyString(), eq(Grant.class))).thenAnswer(call -> grants.get(call.getArgument(0)));
        when(mongo.findById(eq(EMAIL), eq(BillingMember.class)))
                .thenAnswer(call -> imported.get() ? new BillingMember(EMAIL, Instant.now()) : null);
        when(mongo.find(any(Query.class), eq(Grant.class))).thenAnswer(call -> {
            Query query = call.getArgument(0);
            return grants.values().stream()
                    .filter(grant -> grant.email().equals(query.getQueryObject().get("email")))
                    .filter(grant -> !query.getQueryObject().containsKey("kind")
                            || grant.kind().equals(query.getQueryObject().get("kind"))).toList();
        });
        when(mongo.exists(any(Query.class), eq(Account.class))).thenReturn(true);
        when(mongo.exists(any(Query.class), eq(Claim.class))).thenReturn(true);
        when(mongo.save(any(Grant.class))).thenAnswer(call -> {
            Grant grant = call.getArgument(0); grants.put(grant.id(), grant); return grant;
        });
        when(mongo.save(any(BillingMember.class))).thenAnswer(call -> {
            BillingMember member = call.getArgument(0);
            imported.set(true);
            return member;
        });
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(Account.class))).thenAnswer(call -> {
            Query query = call.getArgument(0);
            assertEquals(EMAIL, query.getQueryObject().get("email"));
            assertEquals(Account.Role.MEMBER, query.getQueryObject().get("role"));
            Update update = call.getArgument(1);
            var set = (org.bson.Document) update.getUpdateObject().get("$set");
            assertEquals(Set.of("premium"), set.keySet());
            premium.set(set.getBoolean("premium"));
            return UpdateResult.acknowledged(1, 1L, null);
        });
    }
    private void known(String id, boolean active) {
        grants.put(id, new Grant(id, EMAIL, "subscription", active, "active", Instant.now()));
    }
    private String subscription(String id, String status, String invoiceStatus, boolean cancelAtEnd) {
        return """
            {"id":"%s","object":"subscription","status":"%s","customer":"cus_member",
             "cancel_at_period_end":%s,
             "metadata":{"smartifier_membership":"premium","smartifier_member_email":"%s"},
             "latest_invoice":{"id":"in_latest","object":"invoice","status":"%s"},
             "items":{"object":"list","has_more":false,"data":[
                {"id":"si_member","price":{"id":"price_premium","object":"price"}}]}}
            """.formatted(id, status, cancelAtEnd, EMAIL, invoiceStatus);
    }
    private String sessions(String entries) {
        return "{\"object\":\"list\",\"has_more\":false,\"data\":[" + entries + "]}";
    }
    private String checkout(String id, String mode, String sub) {
        return """
            {"id":"%s","object":"checkout.session","mode":"%s","status":"complete",
             "payment_status":"paid","client_reference_id":"%s","subscription":%s,
             "metadata":{"smartifier_price_id":"price_premium"}}
            """.formatted(id, mode, EMAIL, sub == null ? "null" : "\"" + sub + "\"");
    }

    @Test void billingPortalUsesOwnedSubscriptionEvenWhenPremiumAccessIsSuspended() throws Exception {
        known("sub_member", false);
        try (var api = new FakeStripe(request -> switch (request.path()) {
            case "/v1/subscriptions/sub_member" -> new Reply(200,
                    subscription("sub_member", "past_due", "open", true));
            case "/v1/billing_portal/sessions" -> new Reply(200,
                    "{\"object\":\"billing_portal.session\",\"url\":\"https://billing.stripe.com/p/session/test\"}");
            default -> new Reply(404, "{}");
        })) {
            var stripe = api.client();
            var access = new SubscriptionAccessService(mongo, stripe, pricing);
            var billing = new StripeBillingPortalService(stripe, access, "sk_test_example",
                    "http://localhost:8080/", "bpc_example");
            assertEquals(404, assertThrows(ResponseStatusException.class,
                    () -> billing.open(EMAIL, "sub_other", true)).getStatusCode().value());
            assertTrue(api.requests.isEmpty());
            var membership = billing.memberships(EMAIL).getFirst();
            assertEquals("past_due", membership.status());
            assertTrue(membership.ending());
            assertEquals("https://billing.stripe.com/p/session/test", billing.open(EMAIL, "sub_member", true).url());
            var params = api.requests.getLast().form();
            assertEquals("cus_member", params.get("customer"));
            assertEquals("bpc_example", params.get("configuration"));
            assertEquals("payment_method_update", params.get("flow_data[type]"));
            assertEquals("http://localhost:8080/member/dashboard?tab=billing", params.get("return_url"));
        }
    }

    @Test void billingListsLegacyRecurringCheckoutsAndIgnoresOneTimeOrTerminalMemberships() throws Exception {
        imported.set(false);
        known("sub_ended", false);
        try (var api = new FakeStripe(request -> new Reply(200, switch (request.path()) {
            case "/v1/subscriptions/sub_ended" -> subscription("sub_ended", "canceled", "paid", false);
            case "/v1/subscriptions/sub_legacy" -> subscription("sub_legacy", "active", "paid", false);
            case "/v1/checkout/sessions" -> sessions(checkout("cs_legacy", "subscription", "sub_legacy")
                    + "," + checkout("cs_onetime", "payment", null));
            default -> throw new AssertionError(request.path());
        }))) {
            var access = new SubscriptionAccessService(mongo, api.client(), pricing);
            assertEquals(List.of("sub_legacy"), access.billingSubscriptions(EMAIL).stream()
                    .map(com.stripe.model.Subscription::getId).toList());
        }
    }

    @Test void defaultPortalEnablesPaymentUpdatesAndEndOfPeriodCancellation() throws Exception {
        known("sub_member", true);
        try (var api = new FakeStripe(request -> switch (request.path()) {
            case "/v1/subscriptions/sub_member" -> new Reply(200,
                    subscription("sub_member", "active", "paid", false));
            case "/v1/billing_portal/configurations" -> request.method().equals("GET")
                    ? new Reply(200, sessions(""))
                    : new Reply(200, "{\"object\":\"billing_portal.configuration\",\"id\":\"bpc_created\"}");
            case "/v1/billing_portal/sessions" -> new Reply(200,
                    "{\"object\":\"billing_portal.session\",\"url\":\"https://billing.stripe.com/p/session/test\"}");
            default -> new Reply(404, "{}");
        })) {
            var stripe = api.client();
            var billing = new StripeBillingPortalService(stripe,
                    new SubscriptionAccessService(mongo, stripe, pricing), "sk_test_example", "http://localhost:8080", "");
            billing.open(EMAIL, "sub_member", false);
            billing.open(EMAIL, "sub_member", false);
            var configs = api.requests.stream().filter(request -> request.method().equals("POST")
                    && request.path().equals("/v1/billing_portal/configurations")).toList();
            assertEquals(1, configs.size());
            var features = configs.getFirst().form();
            assertEquals("true", features.get("features[payment_method_update][enabled]"));
            assertEquals("true", features.get("features[customer_update][enabled]"));
            assertEquals("address", features.get("features[customer_update][allowed_updates][1]"));
            assertEquals("true", features.get("features[subscription_cancel][enabled]"));
            assertEquals("at_period_end", features.get("features[subscription_cancel][mode]"));
            assertEquals("bpc_created", api.requests.getLast().form().get("configuration"));
            assertFalse(api.requests.getLast().form().containsKey("flow_data[type]"));
        }
    }

    @Test void failedRenewalRevokesAndSuccessfulRecoveryRestoresAccess() throws Exception {
        known("sub_member", true);
        var status = new java.util.concurrent.atomic.AtomicReference<>("past_due");
        try (var api = new FakeStripe(request -> new Reply(200,
                subscription("sub_member", status.get(), status.get().equals("active") ? "paid" : "open", false)))) {
            var service = new SubscriptionAccessService(mongo, api.client(), pricing);
            service.synchronize("sub_member");
            assertFalse(premium.get());
            assertFalse(grants.get("sub_member").active());
            status.set("active");
            service.synchronize("sub_member");
            assertTrue(premium.get());
            assertTrue(grants.get("sub_member").active());
            assertEquals("latest_invoice", api.requests.getFirst().query().get("expand[0]"));
        }
    }

    @Test void scheduledCancellationRetainsAccessUntilItActuallyEnds() throws Exception {
        known("sub_member", true);
        var canceled = new AtomicBoolean(false);
        try (var api = new FakeStripe(request -> new Reply(200,
                subscription("sub_member", canceled.get() ? "canceled" : "active", "paid", true)))) {
            var service = new SubscriptionAccessService(mongo, api.client(), pricing);
            service.synchronize("sub_member");
            assertTrue(premium.get());
            canceled.set(true);
            service.synchronize("sub_member");
            assertFalse(premium.get());
            service.synchronize("sub_member");
            assertFalse(premium.get());
        }
    }

    @Test void unpaidPausedAndIncompleteSubscriptionsCannotKeepPremium() throws Exception {
        known("sub_member", true);
        for (String status : List.of("unpaid", "paused", "incomplete", "incomplete_expired", "trialing")) {
            try (var api = new FakeStripe(request -> new Reply(200, subscription("sub_member", status, "paid", false)))) {
                new SubscriptionAccessService(mongo, api.client(), pricing).synchronize("sub_member");
                assertFalse(premium.get(), status);
            }
        }
    }

    @Test void activeStatusAloneDoesNotGrantAccessForAnUnpaidInvoice() throws Exception {
        known("sub_member", true);
        try (var api = new FakeStripe(request -> new Reply(200, subscription("sub_member", "active", "open", false)))) {
            new SubscriptionAccessService(mongo, api.client(), pricing).synchronize("sub_member");
            assertFalse(premium.get());
        }
    }

    @Test void anotherPaidSubscriptionPreventsAnOldCancellationFromRevokingAccess() throws Exception {
        known("sub_old", true);
        known("sub_new", true);
        try (var api = new FakeStripe(request -> new Reply(200, request.path().endsWith("sub_old")
                ? subscription("sub_old", "canceled", "paid", false)
                : subscription("sub_new", "active", "paid", false)))) {
            new SubscriptionAccessService(mongo, api.client(), pricing).synchronize("sub_old");
            assertTrue(premium.get());
            assertFalse(grants.get("sub_old").active());
            assertTrue(grants.get("sub_new").active());
        }
    }

    @Test void storedOneTimeGrantDoesNotSurviveSubscriptionCancellation() throws Exception {
        known("sub_member", true);
        grants.put("cs_purchase", new Grant("cs_purchase", EMAIL, "payment", true, "paid", Instant.now()));
        try (var api = new FakeStripe(request -> new Reply(200, subscription("sub_member", "canceled", "paid", false)))) {
            var service = new SubscriptionAccessService(mongo, api.client(), pricing);
            service.synchronize("sub_member");
            assertFalse(premium.get());
            assertEquals(2, grants.size());
            assertEquals("payment", grants.get("cs_purchase").kind());
        }
    }

    @Test void existingSubscriptionsAreRecoveredFromOriginalAppCheckoutRecords() throws Exception {
        imported.set(false);
        try (var api = new FakeStripe(request -> {
            if (request.path().startsWith("/v1/subscriptions/")) {
                return new Reply(200, subscription("sub_member", "canceled", "paid", false).replace(
                        "\"smartifier_membership\":\"premium\",\"smartifier_member_email\":\"" + EMAIL + "\"", ""));
            }
            return new Reply(200, sessions(checkout("cs_original", "subscription", "sub_member")));
        })) {
            new SubscriptionAccessService(mongo, api.client(), pricing).synchronize("sub_member");
            assertFalse(premium.get());
            assertTrue(imported.get());
            assertEquals(EMAIL, grants.get("sub_member").email());
            assertTrue(api.requests.stream().anyMatch(request -> "sub_member".equals(request.query().get("subscription"))));
            assertTrue(api.requests.stream().anyMatch(request -> EMAIL.equals(request.query().get("customer_details[email]"))));
        }
    }

    @Test void legacyOneTimePurchaseIsIgnoredDuringImport() throws Exception {
        imported.set(false);
        known("sub_member", true);
        try (var api = new FakeStripe(request -> new Reply(200, request.path().startsWith("/v1/subscriptions/")
                ? subscription("sub_member", "canceled", "paid", false)
                : sessions(checkout("cs_legacy", "payment", null))))) {
            new SubscriptionAccessService(mongo, api.client(), pricing).synchronize("sub_member");
            assertFalse(premium.get());
            assertFalse(grants.containsKey("cs_legacy"));
        }
    }

    @Test void unrelatedStripeSubscriptionsNeverChangeAnAccount() throws Exception {
        try (var api = new FakeStripe(request -> new Reply(200, request.path().startsWith("/v1/subscriptions/")
                ? subscription("sub_unrelated", "canceled", "paid", false).replace("price_premium", "price_other")
                : sessions("")))) {
            new SubscriptionAccessService(mongo, api.client(), pricing).synchronize("sub_unrelated");
            verify(mongo, never()).updateFirst(any(Query.class), any(Update.class), eq(Account.class));
            assertTrue(grants.isEmpty());
        }
    }

    @Test void delayedFailureWebhookCannotUndoSuccessfulPaymentRecovery() throws Exception {
        known("sub_member", false);
        premium.set(false);
        try (var api = new FakeStripe(request -> new Reply(200, subscription("sub_member", "active", "paid", false)))) {
            var service = new SubscriptionAccessService(mongo, api.client(), pricing);
            var webhook = new StripeWebhookService(service, pricing, new tools.jackson.databind.ObjectMapper(), "whsec_test");
            SubscriptionWebhookTest.deliver(webhook, "invoice.payment_failed", "{\"subscription\":\"sub_member\"}");
            assertTrue(premium.get());
            SubscriptionWebhookTest.deliver(webhook, "invoice.payment_failed", "{\"subscription\":\"sub_member\"}");
            assertTrue(premium.get());
        }
    }

    @Test void delayedPaidCheckoutCannotRestoreACanceledSubscription() throws Exception {
        known("sub_member", true);
        try (var api = new FakeStripe(request -> new Reply(200, subscription("sub_member", "canceled", "paid", false)))) {
            var service = new SubscriptionAccessService(mongo, api.client(), pricing);
            var webhook = new StripeWebhookService(service, pricing, new tools.jackson.databind.ObjectMapper(), "whsec_test");
            SubscriptionWebhookTest.deliver(webhook, "checkout.session.completed", checkout("cs_original", "subscription", "sub_member"));
            assertFalse(premium.get());
        }
    }

    @Test void stripeFailureLeavesAccessUnchangedAndRemainsRetryable() throws Exception {
        known("sub_member", true);
        try (var api = new FakeStripe(request -> new Reply(500,
                "{\"error\":{\"type\":\"api_error\",\"message\":\"private stripe details\"}}"))) {
            var error = assertThrows(ResponseStatusException.class,
                    () -> new SubscriptionAccessService(mongo, api.client(), pricing).synchronize("sub_member"));
            assertEquals(503, error.getStatusCode().value());
            assertFalse(error.getReason().contains("private"));
            verify(mongo, never()).updateFirst(any(Query.class), any(Update.class), eq(Account.class));
            assertTrue(premium.get());
        }
    }

    @Test void concurrentEventsRetryWithoutOverwritingMemberAccess() {
        known("sub_member", true);
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Claim.class)))
                .thenThrow(new DuplicateKeyException("locked"));
        var stripe = mock(com.stripe.StripeClient.class);
        var error = assertThrows(ResponseStatusException.class,
                () -> new SubscriptionAccessService(mongo, stripe, pricing).synchronize("sub_member"));
        assertEquals(503, error.getStatusCode().value());
        verifyNoInteractions(stripe);
        verify(mongo, never()).updateFirst(any(Query.class), any(Update.class), eq(Account.class));
    }
}
