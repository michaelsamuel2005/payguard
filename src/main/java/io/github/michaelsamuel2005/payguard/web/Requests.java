package io.github.michaelsamuel2005.payguard.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Request bodies. Shape is checked here; the domain checks meaning (formats, limits, states) and has the last word. */
public final class Requests {

    private Requests() {
    }

    public record RegisterPayee(@NotBlank @Size(max = 70) String name, @NotBlank String sortCode,
                         @NotBlank String accountNumber) {
    }

    public record InitiatePayment(@NotBlank String payeeId, @Positive long amountPence,
                           @NotBlank @Size(max = 18) String reference) {
    }

    public record ConfirmPayment(@NotBlank String acknowledgedWarning) {
    }

    public record ReviewDecision(@NotBlank String decision, @NotBlank @Size(max = 500) String reason) {
    }
}
