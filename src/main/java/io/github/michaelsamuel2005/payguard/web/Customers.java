package io.github.michaelsamuel2005.payguard.web;

import io.github.michaelsamuel2005.payguard.domain.CustomerId;
import org.springframework.security.oauth2.jwt.Jwt;

/** The customer is the token's subject; nothing in a request body can claim to be someone else. */
final class Customers {

    private Customers() {
    }

    static CustomerId of(Jwt jwt) {
        return new CustomerId(jwt.getSubject());
    }
}
