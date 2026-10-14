package io.github.michaelsamuel2005.payguard.adapters.jdbc;

/** A database error the application cannot handle; the request fails with a 500 and the transaction rolls back. */
public class DataAccessFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DataAccessFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
