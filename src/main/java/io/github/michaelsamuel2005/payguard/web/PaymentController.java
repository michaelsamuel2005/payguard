package io.github.michaelsamuel2005.payguard.web;

import io.github.michaelsamuel2005.payguard.application.AuditEntryView;
import io.github.michaelsamuel2005.payguard.application.InitiateResult;
import io.github.michaelsamuel2005.payguard.application.PaymentService;
import io.github.michaelsamuel2005.payguard.application.PaymentView;
import io.github.michaelsamuel2005.payguard.domain.PayeeId;
import io.github.michaelsamuel2005.payguard.domain.PaymentId;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/payments")
class PaymentController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED = "Idempotent-Replayed";

    private final PaymentService payments;

    PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    /**
     * 201 for a new payment; 200 with {@code Idempotent-Replayed: true} for a retry of an earlier request with the
     * same key and body; 409 if the key was used for a different body.
     */
    @PostMapping
    ResponseEntity<PaymentView> initiate(@AuthenticationPrincipal Jwt jwt,
                                         @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
                                         @Valid @RequestBody Requests.InitiatePayment body) {
        InitiateResult result = payments.initiate(Customers.of(jwt), idempotencyKey, PayeeId.parse(body.payeeId()),
                body.amountPence(), body.reference());
        if (result.replayed()) {
            return ResponseEntity.ok().header(REPLAYED, "true").body(result.payment());
        }
        return ResponseEntity.created(URI.create("/v1/payments/" + result.payment().id())).body(result.payment());
    }

    @GetMapping("/{id}")
    PaymentView get(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") String id) {
        return payments.get(Customers.of(jwt), PaymentId.parse(id));
    }

    @GetMapping("/{id}/audit")
    List<AuditEntryView> audit(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") String id) {
        return payments.audit(Customers.of(jwt), PaymentId.parse(id));
    }

    /** Releases a warned payment; the body must name the warning the customer was shown. */
    @PostMapping("/{id}/confirm")
    PaymentView confirm(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") String id,
                        @Valid @RequestBody Requests.ConfirmPayment body) {
        return payments.confirm(Customers.of(jwt), PaymentId.parse(id), body.acknowledgedWarning());
    }

    @PostMapping("/{id}/cancel")
    PaymentView cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") String id) {
        return payments.cancel(Customers.of(jwt), PaymentId.parse(id));
    }
}
