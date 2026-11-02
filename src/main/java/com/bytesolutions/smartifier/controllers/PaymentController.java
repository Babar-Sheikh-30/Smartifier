package com.bytesolutions.smartifier.controllers;

import java.security.Principal;
import com.bytesolutions.smartifier.services.StripeCheckoutService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentController {
    private final StripeCheckoutService checkout;

    public PaymentController(StripeCheckoutService checkout) { this.checkout = checkout; }

    @PostMapping("/api/member/payments/checkout")
    public StripeCheckoutService.Checkout checkout(Principal principal) {
        return checkout.create(principal.getName());
    }
}
