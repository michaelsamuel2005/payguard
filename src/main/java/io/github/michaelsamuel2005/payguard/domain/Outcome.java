package io.github.michaelsamuel2005.payguard.domain;

/** What happens to a payment once it has been assessed. */
public enum Outcome {
    /** Released straight away. */
    ALLOW,
    /** Shown a specific warning; released only if the customer confirms they have read it. */
    WARN,
    /** Stopped for a person in the review team to approve or reject. */
    HOLD
}
