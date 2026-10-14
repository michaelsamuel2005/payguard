package io.github.michaelsamuel2005.payguard.domain;

/** A request the domain refuses as malformed or out of range. Maps to HTTP 400. */
public class InvalidRequestException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidRequestException(String message) {
        super(message);
    }
}
