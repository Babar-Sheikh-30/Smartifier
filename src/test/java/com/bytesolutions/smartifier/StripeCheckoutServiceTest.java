package com.bytesolutions.smartifier;

import com.bytesolutions.smartifier.services.StripeCheckoutService;
import com.bytesolutions.smartifier.config.StripeConfig;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class StripeCheckoutServiceTest {
    @Test
    void checkoutServiceCanStartWithoutPaymentCredentials() {
        try (var context = new AnnotationConfigApplicationContext()) {
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                    "smartifier.stripe.secret-key=", "smartifier.stripe.price-id=",
                    "smartifier.stripe.mode=payment", "smartifier.public-url=http://localhost:8080");
            context.register(StripeConfig.class, StripeCheckoutService.class);
            context.refresh();
            assertNotNull(context.getBean(StripeCheckoutService.class));
        }
    }

    @Test
    void createsCheckoutWithServerPriceAndAuthenticatedEmail() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var service = service(builder, "sk_test_example", "price_premium", "payment");
        server.expect(requestTo("https://api.stripe.com/v1/checkout/sessions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer sk_test_example"))
                .andExpect(content().formDataContains(java.util.Map.of(
                        "line_items[0][price]", "price_premium", "line_items[0][quantity]", "1",
                        "customer_email", "member@example.com", "mode", "payment",
                        "success_url", "http://localhost:8080/member/dashboard?checkout=returned",
                        "cancel_url", "http://localhost:8080/member/dashboard?checkout=cancelled")))
                .andRespond(withSuccess("{\"url\":\"https://checkout.stripe.com/c/pay/test\"}", MediaType.APPLICATION_JSON));
        assertEquals("https://checkout.stripe.com/c/pay/test", service.create("member@example.com").url());
        server.verify();
    }

    @Test
    void rejectsMissingConfigurationBeforeContactingStripe() {
        var service = service(RestClient.builder(), "", "", "payment");
        var error = assertThrows(ResponseStatusException.class, () -> service.create("member@example.com"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
    }

    @Test
    void rejectsUnexpectedRedirectsAndHidesStripeErrors() {
        for (String response : new String[] {"{\"url\":\"https://example.com/pay\"}", "{}"}) {
            var builder = RestClient.builder();
            var server = MockRestServiceServer.bindTo(builder).build();
            var service = service(builder, "sk_test_example", "price_premium", "subscription");
            server.expect(anything()).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
            assertEquals(HttpStatus.BAD_GATEWAY, assertThrows(ResponseStatusException.class,
                    () -> service.create("member@example.com")).getStatusCode());
            server.verify();
        }
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var service = service(builder, "sk_test_example", "price_premium", "payment");
        server.expect(anything()).andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("private Stripe details"));
        var error = assertThrows(ResponseStatusException.class, () -> service.create("member@example.com"));
        assertEquals(HttpStatus.BAD_GATEWAY, error.getStatusCode());
        assertFalse(error.getReason().contains("private"));
        server.verify();
    }

    private StripeCheckoutService service(RestClient.Builder builder, String key, String price, String mode) {
        return new StripeCheckoutService(builder, key, price, mode, "http://localhost:8080/");
    }
}
