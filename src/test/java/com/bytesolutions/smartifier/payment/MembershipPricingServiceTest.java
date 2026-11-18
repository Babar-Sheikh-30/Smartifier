package com.bytesolutions.smartifier.payment;

import com.bytesolutions.smartifier.payment.MembershipPricingService.*;
import com.stripe.StripeClient;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MembershipPricingServiceTest {
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private Settings saved() {
        return new Settings("premium", 3L, "price_new", List.of("price_old", "price_new"), "admin", Instant.now());
    }
    private Migration migration() {
        return new Migration("review-1", "admin", "sub_member", "si_member", "price_old", "price_new", 1L,
                Instant.now().plusSeconds(600), "running");
    }
    private void claim() {
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Migration.class)))
                .thenReturn(migration());
        when(mongo.findById("premium", Settings.class)).thenReturn(saved());
    }
    private MembershipPricingService service(StripeClient client) {
        return new MembershipPricingService(mongo, client, "sk_test_example", "price_old");
    }
    private static String price(String id, long amount, String interval) {
        return """
            {"id":"%s","object":"price","currency":"cad","unit_amount":%d,"product":"prod_premium",
             "billing_scheme":"per_unit","active":true,"livemode":false,"tax_behavior":"exclusive",
             "recurring":{"interval":"%s","interval_count":1,"usage_type":"licensed"}}
            """.formatted(id, amount, interval);
    }
    private static String subscription(String price, String extra) {
        return """
            {"id":"sub_member","object":"subscription","status":"active","collection_method":"charge_automatically",%s
             "items":{"object":"list","has_more":false,"data":[
               {"id":"si_member","object":"subscription_item","quantity":1,"price":%s}]}}
            """.formatted(extra, price);
    }

    @Test void savesARecurringUsdPriceAndPreservesHistoricalCadCheckoutPrices() throws Exception {
        when(mongo.save(any(Settings.class))).thenAnswer(call -> {
            Settings setting = call.getArgument(0);
            return new Settings(setting.id(), 0L, setting.priceId(), setting.priceHistory(), setting.updatedBy(), setting.updatedAt());
        });
        try (var api = new FakeStripe(request -> new Reply(200, request.method().equals("GET")
                ? price("price_old", 1000, "month") : price("price_new", 10000, "year").replace("cad", "usd")))) {
            var result = service(api.client()).save(10000, "usd", "year", -1, "admin@example.com");
            assertEquals("price_new", result.priceId());
            assertEquals("usd", result.currency());
            assertEquals("year", result.interval());
            var create = api.requests.getLast();
            assertEquals("/v1/prices", create.path());
            assertEquals("10000", create.form().get("unit_amount"));
            assertEquals("usd", create.form().get("currency"));
            assertEquals("year", create.form().get("recurring[interval]"));
            assertEquals("prod_premium", create.form().get("product"));
            assertEquals("exclusive", create.form().get("tax_behavior"));
            var settings = ArgumentCaptor.forClass(Settings.class);
            verify(mongo).save(settings.capture());
            assertEquals(List.of("price_old", "price_new"), settings.getValue().priceHistory());
            assertEquals("admin@example.com", settings.getValue().updatedBy());
        }
    }

    @Test void rejectsStaleOrInvalidChangesBeforeCallingStripe() {
        var stripe = mock(StripeClient.class);
        var service = service(stripe);
        for (String currency : List.of("jpy", "invalid")) {
            assertThrows(ResponseStatusException.class, () -> service.save(1000, currency, "month", -1, "admin"));
        }
        assertThrows(ResponseStatusException.class, () -> service.save(0, "cad", "month", -1, "admin"));
        assertThrows(ResponseStatusException.class, () -> service.save(1000, "cad", "week", -1, "admin"));
        when(mongo.findById("premium", Settings.class)).thenReturn(saved());
        assertThrows(ResponseStatusException.class, () -> service.save(1000, "cad", "month", 2, "admin"));
        verifyNoInteractions(stripe);
    }

    @Test void refusesCustomBillingWithoutCreatingAPrice() throws Exception {
        try (var api = new FakeStripe(request -> new Reply(200,
                price("price_old", 1000, "month").replace("per_unit", "tiered")))) {
            assertThrows(ResponseStatusException.class, () -> service(api.client()).save(1000, "usd", "month", -1, "admin"));
            assertTrue(api.requests.stream().allMatch(request -> request.method().equals("GET")));
            verify(mongo, never()).save(any(Settings.class));
        }
    }

    @Test void previewsOnlyEligibleSubscriptionsOnHistoricalPrices() throws Exception {
        when(mongo.findById("premium", Settings.class)).thenReturn(saved());
        try (var api = new FakeStripe(request -> new Reply(200, switch (request.path()) {
            case "/v1/prices/price_new" -> price("price_new", 10000, "year");
            case "/v1/prices/price_old" -> price("price_old", 1000, "month");
            default -> "{\"object\":\"list\",\"has_more\":false,\"data\":["
                    + subscription(price("price_old", 1000, "month"), "") + ","
                    + subscription(price("price_old", 1000, "month"), "\"schedule\":\"sub_sched_1\",") + "]}";
        }))) {
            var result = service(api.client()).preview("price_new", null, "admin");
            assertEquals(1, result.candidates().size());
            assertEquals(1, result.skipped());
            assertEquals("price_old", api.requests.getLast().query().get("price"));
            assertEquals("active", api.requests.getLast().query().get("status"));
            var migration = ArgumentCaptor.forClass(Migration.class);
            verify(mongo).insert(migration.capture());
            assertEquals("admin", migration.getValue().admin());
            assertEquals("price_new", migration.getValue().targetPrice());
            assertTrue(migration.getValue().expiresAt().isAfter(Instant.now()));
            assertTrue(api.requests.stream().allMatch(request -> request.method().equals("GET")));
        }
    }

    @Test void skipsHistoricalOneTimeCheckoutPricesDuringSubscriptionReview() throws Exception {
        when(mongo.findById("premium", Settings.class)).thenReturn(saved());
        try (var api = new FakeStripe(request -> switch (request.path()) {
            case "/v1/prices/price_new" -> new Reply(200, price("price_new", 10000, "year"));
            case "/v1/prices/price_old" -> new Reply(200, """
                    {"id":"price_old","object":"price","currency":"cad","type":"one_time",
                     "unit_amount":1000,"billing_scheme":"per_unit"}
                    """);
            default -> new Reply(400, "{\"error\":{\"type\":\"invalid_request_error\"}}");
        })) {
            assertTrue(service(api.client()).preview("price_new", null, "admin").candidates().isEmpty());
            assertTrue(api.requests.stream().noneMatch(request -> request.path().equals("/v1/subscriptions")));
        }
    }

    @Test void reviewCanContinuePastAFailedBatchWithoutRepeatingTheFirstPage() throws Exception {
        when(mongo.findById("premium", Settings.class)).thenReturn(saved());
        String subscriptions = java.util.stream.IntStream.rangeClosed(1, 26)
                .mapToObj(index -> subscription(price("price_old", 1000, "month"), "")
                        .replace("sub_member", "sub_" + index)).collect(Collectors.joining(","));
        try (var api = new FakeStripe(request -> new Reply(200, switch (request.path()) {
            case "/v1/prices/price_new" -> price("price_new", 10000, "year");
            case "/v1/prices/price_old" -> price("price_old", 1000, "month");
            default -> "{\"object\":\"list\",\"has_more\":false,\"data\":["
                    + (request.query().containsKey("starting_after")
                        ? subscription(price("price_old", 1000, "month"), "").replace("sub_member", "sub_26")
                        : subscriptions) + "]}";
        }))) {
            var service = service(api.client());
            var first = service.preview("price_new", null, "admin");
            assertEquals(25, first.candidates().size());
            assertEquals("0:sub_25", first.nextCursor());
            var next = service.preview("price_new", first.nextCursor(), "admin");
            assertEquals(1, next.candidates().size());
            assertEquals("sub_26", next.candidates().getFirst().subscriptionId());
            assertEquals("sub_25", api.requests.getLast().query().get("starting_after"));
            assertFalse(next.more());
        }
    }

    @Test void appliesReviewedTermsUsingStripeSdkWithImmediateProrationAndIdempotency() throws Exception {
        claim();
        try (var api = new FakeStripe(request -> new Reply(200, subscription(
                price(request.method().equals("GET") ? "price_old" : "price_new", 1000, "month"), "")))) {
            assertEquals("updated", service(api.client()).apply("review-1", "admin").status());
            var update = api.requests.getLast();
            assertEquals("POST", update.method());
            assertEquals("/v1/subscriptions/sub_member", update.path());
            assertEquals("si_member", update.form().get("items[0][id]"));
            assertEquals("price_new", update.form().get("items[0][price]"));
            assertEquals("1", update.form().get("items[0][quantity]"));
            assertEquals("always_invoice", update.form().get("proration_behavior"));
            assertEquals("error_if_incomplete", update.form().get("payment_behavior"));
            assertEquals("smartifier-pricing-review-1", update.idempotencyKey());
            verify(mongo).remove(any(Query.class), eq(BillingClaim.class));
        }
    }

    @Test void changedSubscriptionsAreSkippedWithoutBilling() throws Exception {
        claim();
        try (var api = new FakeStripe(request -> new Reply(200, subscription(price("price_elsewhere", 1000, "month"), "")))) {
            assertEquals("skipped", service(api.client()).apply("review-1", "admin").status());
            assertEquals(1, api.requests.size());
            assertEquals("GET", api.requests.getFirst().method());
        }
    }

    @Test void stripePaymentFailuresAreReportedWithoutLeakingProviderDetails() throws Exception {
        claim();
        try (var api = new FakeStripe(request -> request.method().equals("GET")
                ? new Reply(200, subscription(price("price_old", 1000, "month"), ""))
                : new Reply(402, "{\"error\":{\"type\":\"card_error\",\"message\":\"sensitive provider details\"}}"))) {
            var result = service(api.client()).apply("review-1", "admin");
            assertEquals("failed", result.status());
            assertFalse(result.message().contains("sensitive"));
        }
    }

    @Test void consumedExpiredOrOtherAdminReviewsCannotCallStripe() {
        var stripe = mock(StripeClient.class);
        assertThrows(ResponseStatusException.class, () -> service(stripe).apply("review-1", "other-admin"));
        var query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).findAndModify(query.capture(), any(Update.class), any(FindAndModifyOptions.class), eq(Migration.class));
        assertEquals("other-admin", query.getValue().getQueryObject().get("admin"));
        assertEquals("pending", query.getValue().getQueryObject().get("state"));
        assertTrue(query.getValue().getQueryObject().containsKey("expiresAt"));
        verifyNoInteractions(stripe);
    }

    @Test void concurrentSubscriptionChangesCannotCallStripe() {
        claim();
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(BillingClaim.class)))
                .thenThrow(new DuplicateKeyException("claimed"));
        var stripe = mock(StripeClient.class);
        assertEquals("failed", service(stripe).apply("review-1", "admin").status());
        verifyNoInteractions(stripe);
    }

    @Test void historicPricesRemainAcceptedButUnknownPricesDoNot() {
        when(mongo.findById("premium", Settings.class)).thenReturn(saved());
        var service = service(mock(StripeClient.class));
        assertTrue(service.accepts("price_old"));
        assertTrue(service.accepts("price_new"));
        assertFalse(service.accepts("price_unrelated"));
        assertFalse(service.accepts(""));
        assertEquals("price_new", service.checkoutPrice());
    }

    @Test void checkoutUsesTheSavedPriceAndModeInsteadOfTheInitialEnvironmentValues() throws Exception {
        try (var api = new FakeStripe(request -> new Reply(200,
                "{\"object\":\"checkout.session\",\"url\":\"https://checkout.stripe.com/c/pay/test\"}"))) {
            var pricing = mock(MembershipPricingService.class);
            when(pricing.checkoutPrice()).thenReturn("price_new");
            var checkout = new StripeCheckoutService(api.client(), pricing, "sk_test_example", "https://smartifier.example");
            checkout.create("member@example.com");
            assertEquals("price_new", api.requests.getFirst().form().get("line_items[0][price]"));
            assertEquals("subscription", api.requests.getFirst().form().get("mode"));
        }
    }

    @Test void signedWebhookStillAcceptsHistoricalCheckoutAfterPriceChanges() throws Exception {
        var pricing = mock(MembershipPricingService.class);
        when(pricing.accepts("price_historical")).thenReturn(true);
        var access = mock(SubscriptionAccessService.class);
        var webhook = new StripeWebhookService(access, pricing, new tools.jackson.databind.ObjectMapper(), "whsec_test");
        byte[] body = """
            {"type":"checkout.session.completed","data":{"object":{"id":"cs_historic","mode":"subscription","payment_status":"paid","subscription":"sub_historic",
             "client_reference_id":"member@example.com","metadata":{"smartifier_price_id":"price_historical"}}}}
            """.getBytes(StandardCharsets.UTF_8);
        long timestamp = Instant.now().getEpochSecond();
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec("whsec_test".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
        String signature = "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(mac.doFinal(body));
        webhook.receive(body, signature);
        verify(access).synchronize("sub_historic");
    }

    record Request(String method, String path, Map<String, String> query, Map<String, String> form, String idempotencyKey) {}
    record Reply(int status, String body) {}
    static class FakeStripe implements AutoCloseable {
        final HttpServer server;
        final List<Request> requests = new CopyOnWriteArrayList<>();
        FakeStripe(Function<Request, Reply> handler) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try {
                    var request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                            decode(exchange.getRequestURI().getRawQuery()),
                            decode(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)),
                            exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                    requests.add(request);
                    Reply reply = handler.apply(request);
                    byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(reply.status(), bytes.length);
                    exchange.getResponseBody().write(bytes);
                } finally { exchange.close(); }
            });
            server.start();
        }
        StripeClient client() {
            return StripeClient.builder().setApiKey("sk_test_example")
                    .setApiBase("http://127.0.0.1:" + server.getAddress().getPort())
                    .setConnectTimeout(2000).setReadTimeout(2000).setMaxNetworkRetries(0).build();
        }
        private static Map<String, String> decode(String body) {
            if (body == null || body.isBlank()) return Map.of();
            return Arrays.stream(body.split("&")).map(part -> part.split("=", 2)).collect(Collectors.toMap(
                    pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
        }
        public void close() { server.stop(0); }
    }
}
