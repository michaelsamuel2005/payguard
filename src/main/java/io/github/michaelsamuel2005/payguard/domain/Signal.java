package io.github.michaelsamuel2005.payguard.domain;

/**
 * Something about a payment that scams have in common. Each has a fixed weight; a payment's risk score is the sum
 * of the weights of its signals, and {@link ScamPolicy} turns the score into allow, warn or hold.
 */
public enum Signal {
    NEW_PAYEE(1, "the first payment to this payee"),
    NAME_CLOSE_MATCH(1, "the name is close to, but not the same as, the name on the account"),
    NAME_NO_MATCH(3, "the name does not match the name on the account"),
    NAME_CHECK_UNAVAILABLE(2, "the name on the account could not be checked"),
    UNUSUAL_AMOUNT(2, "much larger than this customer's usual payments"),
    SCAM_LANGUAGE(2, "the reference uses words common in scams"),
    RAPID_NEW_PAYEES(2, "one of several payments to new payees in a short time");

    private final int weight;
    private final String description;

    Signal(int weight, String description) {
        this.weight = weight;
        this.description = description;
    }

    public int weight() {
        return weight;
    }

    public String description() {
        return description;
    }
}
