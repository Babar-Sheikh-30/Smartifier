package com.bytesolutions.smartifier.payment;

import java.security.Principal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentController {
    private final StripeCheckoutService checkout;
    private final StripeBillingPortalService billing;

    public PaymentController(StripeCheckoutService checkout, StripeBillingPortalService billing) {
        this.checkout = checkout;
        this.billing = billing;
    }

    @PostMapping("/api/member/payments/checkout")
    public StripeCheckoutService.Checkout checkout(Principal principal) {
        return checkout.create(principal.getName());
    }

    @GetMapping("/api/member/payments/memberships")
    public List<StripeBillingPortalService.Membership> memberships(Principal principal) {
        return billing.memberships(principal.getName());
    }

    @PostMapping("/api/member/payments/memberships/{id}/portal")
    public StripeBillingPortalService.Portal portal(Principal principal, @PathVariable String id,
            @RequestParam(defaultValue = "false") boolean paymentMethod) {
        return billing.open(principal.getName(), id, paymentMethod);
    }
}
