package com.bytesolutions.smartifier.payment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/membership-pricing")
public class AdminPricingController {
    private final MembershipPricingService service;
    public AdminPricingController(MembershipPricingService service) { this.service = service; }
    public record Change(@Min(1) @Max(99999999) long amount, @NotBlank String currency,
            @NotBlank String interval, @NotNull Long expectedRevision) {}
    public record Review(@NotBlank String priceId, String cursor) {}
    @GetMapping public MembershipPricingService.Pricing get() { return service.get(); }
    @PostMapping public MembershipPricingService.Pricing save(@Valid @RequestBody Change change, Principal admin) {
        return service.save(change.amount(), change.currency(), change.interval(), change.expectedRevision(), admin.getName());
    }
    @PostMapping("/preview") public MembershipPricingService.Preview preview(@Valid @RequestBody Review review, Principal admin) {
        return service.preview(review.priceId(), review.cursor(), admin.getName());
    }
    @PostMapping("/apply/{id}") public MembershipPricingService.Outcome apply(@PathVariable String id, Principal admin) {
        return service.apply(id, admin.getName());
    }
}
