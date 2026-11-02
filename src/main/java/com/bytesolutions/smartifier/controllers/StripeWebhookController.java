package com.bytesolutions.smartifier.controllers;

import com.bytesolutions.smartifier.services.StripeWebhookService;
import org.springframework.web.bind.annotation.*;

@RestController
public class StripeWebhookController {
    private final StripeWebhookService webhook;

    public StripeWebhookController(StripeWebhookService webhook) { this.webhook = webhook; }

    @PostMapping("/api/payments/stripe/webhook")
    // Keep the raw body intact because Stripe's signature covers its exact bytes.
    public void receive(@RequestBody byte[] body,
            @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
        webhook.receive(body, signature);
    }
}
