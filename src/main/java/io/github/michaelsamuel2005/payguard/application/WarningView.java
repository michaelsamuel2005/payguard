package io.github.michaelsamuel2005.payguard.application;

import io.github.michaelsamuel2005.payguard.domain.WarningCode;

public record WarningView(String code, String message) {

    public static WarningView of(WarningCode code) {
        return code == null ? null : new WarningView(code.name(), code.message());
    }
}
