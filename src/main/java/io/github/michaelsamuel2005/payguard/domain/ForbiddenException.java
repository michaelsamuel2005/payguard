package io.github.michaelsamuel2005.payguard.domain;

/** The caller is authenticated but this action is not theirs to take. Maps to HTTP 403. */
public class ForbiddenException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ForbiddenException(String message) {
        super(message);
    }
}
