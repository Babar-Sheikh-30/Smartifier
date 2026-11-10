package com.bytesolutions.smartifier.content;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/reviews")
public class AdminReviewController {
    private final AdminReviewService reviews;
    public AdminReviewController(AdminReviewService reviews) { this.reviews = reviews; }
    public record Decision(@NotNull @PositiveOrZero Long expectedRevision, @NotNull Boolean approved,
            @Size(max = 500) String note) {}

    @GetMapping public List<AdminReviewService.Entry> pending() { return reviews.pending(); }

    @PostMapping("/{stackId}/versions/{number}/decision")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decide(@PathVariable String stackId, @PathVariable int number, @Valid @RequestBody Decision decision) {
        reviews.decide(stackId, number, decision.expectedRevision(), decision.approved(), decision.note());
    }
}
