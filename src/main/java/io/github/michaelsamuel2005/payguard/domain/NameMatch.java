package io.github.michaelsamuel2005.payguard.domain;

/** The result of comparing the name a payer typed with the name on the account, as in Confirmation of Payee. */
public enum NameMatch {
    MATCH,
    CLOSE_MATCH,
    NO_MATCH,
    /** The check could not be made (the directory was down or too slow). Never treated as a pass. */
    UNAVAILABLE
}
