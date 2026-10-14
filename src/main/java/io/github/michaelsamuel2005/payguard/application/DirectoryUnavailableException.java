package io.github.michaelsamuel2005.payguard.application;

public class DirectoryUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DirectoryUnavailableException(String message) {
        super(message);
    }

    public DirectoryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
