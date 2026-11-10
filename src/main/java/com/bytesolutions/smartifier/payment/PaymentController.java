package com.bytesolutions.smartifier.payment;

import java.security.Principal;
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
