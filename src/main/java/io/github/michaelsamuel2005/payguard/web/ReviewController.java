package io.github.michaelsamuel2005.payguard.web;

import io.github.michaelsamuel2005.payguard.application.AuditEntryView;
import io.github.michaelsamuel2005.payguard.application.PaymentView;
import io.github.michaelsamuel2005.payguard.application.ReviewService;
import io.github.michaelsamuel2005.payguard.domain.PaymentId;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** For the review team (scope {@code reviews}): held payments, oldest first, and decisions with reasons. */
@RestController
@RequestMapping("/v1/reviews")
class ReviewController {

    private final ReviewService reviews;

    ReviewController(ReviewService reviews) {
        this.reviews = reviews;
    }

    @GetMapping
    List<PaymentView> queue(@RequestParam(name = "limit", defaultValue = "50") int limit) {
        return reviews.queue(limit);
    }

    /** The reviewer is the token's subject, and may not be the payment's customer. */
    @PostMapping("/{id}")
    PaymentView decide(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") String id,
                       @Valid @RequestBody Requests.ReviewDecision body) {
        return reviews.decide(jwt.getSubject(), PaymentId.parse(id), body.decision(), body.reason());
    }

    @GetMapping("/{id}/audit")
    List<AuditEntryView> audit(@PathVariable("id") String id) {
        return reviews.audit(PaymentId.parse(id));
    }
}
