package io.github.michaelsamuel2005.payguard.web;

import io.github.michaelsamuel2005.payguard.application.PayeeService;
import io.github.michaelsamuel2005.payguard.application.PayeeView;
import io.github.michaelsamuel2005.payguard.domain.PayeeId;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/payees")
class PayeeController {

    private final PayeeService payees;

    PayeeController(PayeeService payees) {
        this.payees = payees;
    }

    /** Checks the name against the account and saves the payee with the result. */
    @PostMapping
    ResponseEntity<PayeeView> register(@AuthenticationPrincipal Jwt jwt,
                                       @Valid @RequestBody Requests.RegisterPayee body) {
        PayeeView view = payees.register(Customers.of(jwt), body.name(), body.sortCode(), body.accountNumber());
        return ResponseEntity.created(URI.create("/v1/payees/" + view.id())).body(view);
    }

    @GetMapping
    List<PayeeView> list(@AuthenticationPrincipal Jwt jwt) {
        return payees.list(Customers.of(jwt));
    }

    @GetMapping("/{id}")
    PayeeView get(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") String id) {
        return payees.get(Customers.of(jwt), PayeeId.parse(id));
    }
}
