package io.github.michaelsamuel2005.payguard.domain;

/** The request is valid but conflicts with the current state. Maps to HTTP 409. */
public class ConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ConflictException(String message) {
        super(message);
    }
}
