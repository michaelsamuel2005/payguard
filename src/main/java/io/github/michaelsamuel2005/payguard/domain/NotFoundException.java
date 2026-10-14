package io.github.michaelsamuel2005.payguard.domain;

/**
 * Nothing found that the caller may see. Also used when a resource exists but belongs to someone else, so a
 * caller cannot learn whether another customer's payee or payment exists. Maps to HTTP 404.
 */
public class NotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public NotFoundException(String message) {
        super(message);
    }
}
