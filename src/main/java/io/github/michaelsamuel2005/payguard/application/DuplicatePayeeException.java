package io.github.michaelsamuel2005.payguard.application;

import io.github.michaelsamuel2005.payguard.domain.ConflictException;

public class DuplicatePayeeException extends ConflictException {
    private static final long serialVersionUID = 1L;

    public DuplicatePayeeException() {
        super("you already have a payee with this sort code and account number");
    }
}
