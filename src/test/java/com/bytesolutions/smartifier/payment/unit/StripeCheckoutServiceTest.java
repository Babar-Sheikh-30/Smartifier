package com.bytesolutions.smartifier.payment.unit;

import com.bytesolutions.smartifier.config.StripeConfig;
import com.bytesolutions.smartifier.payment.MembershipPricingService;
import com.bytesolutions.smartifier.payment.StripeCheckoutService;
import com.stripe.StripeClient;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StripeCheckoutServiceTest {
    @Test
    void checkoutServiceCanStartWithoutPaymentCredentials() {
        try (var context = new AnnotationConfigApplicationContext()) {
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                    "smartifier.stripe.secret-key=",
                    "smartifier.public-url=http://localhost:8080");
            var pricing = mock(MembershipPricingService.class);
            context.registerBean(MembershipPricingService.class, () -> pricing);
            context.register(StripeConfig.class, StripeCheckoutService.class);
            context.refresh();
            var service = context.getBean(StripeCheckoutService.class);
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ResponseStatusException.class,
                    () -> service.create("member@example.com")).getStatusCode());
            verifyNoInteractions(pricing);
        }
    }

    @Test
    void createsCheckoutWithServerPriceAndAuthenticatedEmail() throws Exception {
        try (var endpoint = new FakeStripe(200,
                "{\"object\":\"checkout.session\",\"url\":\"https://checkout.stripe.com/c/pay/test\"}")) {
            var service = service(endpoint.client(), "sk_test_example", "price_premium");
            assertEquals("https://checkout.stripe.com/c/pay/test", service.create("member@example.com").url());
            assertEquals("POST", endpoint.method.get());
            assertEquals("/v1/checkout/sessions", endpoint.path.get());
            assertEquals("Bearer sk_test_example", endpoint.authorization.get());
            var form = endpoint.form.get();
            assertEquals("price_premium", form.get("line_items[0][price]"));
            assertEquals("1", form.get("line_items[0][quantity]"));
            assertEquals("member@example.com", form.get("customer_email"));
            assertEquals("member@example.com", form.get("client_reference_id"));
            assertEquals("subscription", form.get("mode"));
            assertEquals("price_premium", form.get("metadata[smartifier_price_id]"));
            assertEquals("member@example.com", form.get("subscription_data[metadata][smartifier_member_email]"));
            assertEquals("premium", form.get("subscription_data[metadata][smartifier_membership]"));
            assertFalse(form.containsKey("subscription_data[metadata][smartifier_price_id]"));
            assertEquals("http://localhost:8080/member/dashboard?checkout=returned", form.get("success_url"));
            assertEquals("http://localhost:8080/member/dashboard?checkout=cancelled", form.get("cancel_url"));
        }
    }

    @Test
    void rejectsMissingOrInvalidConfigurationBeforeContactingStripe() {
        var client = mock(StripeClient.class);
        for (String[] config : new String[][] {{"", "price_premium"},
                {"sk_test_example", ""}}) {
            var service = service(client, config[0], config[1]);
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ResponseStatusException.class,
                    () -> service.create("member@example.com")).getStatusCode());
        }
        verifyNoInteractions(client);
    }

    @Test
    void rejectsUnexpectedRedirectsAndHidesStripeErrors() throws Exception {
        for (String url : new String[] {"https://example.com/pay", "http://checkout.stripe.com/pay",
                "https://user@checkout.stripe.com/pay", "https://checkout.stripe.com:443/pay", "invalid"}) {
            try (var endpoint = new FakeStripe(200, "{\"url\":\"" + url + "\"}")) {
                assertBadGateway(endpoint);
            }
        }
        try (var endpoint = new FakeStripe(200, "{}")) {
            assertBadGateway(endpoint);
        }
        try (var endpoint = new FakeStripe(401,
                "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"private Stripe details\"}}")) {
            var error = assertBadGateway(endpoint);
            assertFalse(error.getReason().contains("private"));
        }
    }

    private ResponseStatusException assertBadGateway(FakeStripe endpoint) {
        var error = assertThrows(ResponseStatusException.class,
                () -> service(endpoint.client(), "sk_test_example", "price_premium")
                        .create("member@example.com"));
        assertEquals(HttpStatus.BAD_GATEWAY, error.getStatusCode());
        return error;
    }

    private StripeCheckoutService service(StripeClient client, String key, String price) {
        var pricing = mock(MembershipPricingService.class);
        when(pricing.checkoutPrice()).thenReturn(price);
        return new StripeCheckoutService(client, pricing, key, "http://localhost:8080/");
    }

    // Exercise the real SDK against a local endpoint, never Stripe.
    private static class FakeStripe implements AutoCloseable {
        final HttpServer server;
        final AtomicReference<String> method = new AtomicReference<>();
        final AtomicReference<String> path = new AtomicReference<>();
        final AtomicReference<String> authorization = new AtomicReference<>();
        final AtomicReference<Map<String, String>> form = new AtomicReference<>();

        FakeStripe(int status, String response) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try {
                    method.set(exchange.getRequestMethod());
                    path.set(exchange.getRequestURI().getPath());
                    authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    form.set(Arrays.stream(body.split("&")).map(part -> part.split("=", 2))
                            .collect(Collectors.toMap(pair -> decode(pair[0]), pair -> decode(pair[1]))));
                    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } finally {
                    exchange.close();
                }
            });
            server.start();
        }

        StripeClient client() {
            return StripeClient.builder().setApiKey("sk_test_example")
                    .setApiBase("http://127.0.0.1:" + server.getAddress().getPort())
                    .setConnectTimeout(2_000).setReadTimeout(2_000).setMaxNetworkRetries(0).build();
        }

        private static String decode(String value) {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        }

        @Override public void close() { server.stop(0); }
    }
}
