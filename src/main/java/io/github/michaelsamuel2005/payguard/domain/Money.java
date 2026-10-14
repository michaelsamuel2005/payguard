package io.github.michaelsamuel2005.payguard.domain;

/**
 * An amount of pounds sterling, held in pence so that no arithmetic is ever done in floating point.
 * Zero is allowed here (for sums); a payment additionally requires a positive amount.
 */
public record Money(long pence) implements Comparable<Money> {

    public static final Money ZERO = new Money(0);

    public Money {
        if (pence < 0) {
            throw new InvalidRequestException("an amount cannot be negative");
        }
    }

    public static Money ofPence(long pence) {
        return new Money(pence);
    }

    public static Money ofPounds(long pounds) {
        return new Money(Math.multiplyExact(pounds, 100L));
    }

    public Money times(long factor) {
        return new Money(Math.multiplyExact(pence, factor));
    }

    public boolean isGreaterThan(Money other) {
        return pence > other.pence;
    }

    public boolean isAtLeast(Money other) {
        return pence >= other.pence;
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(pence, other.pence);
    }

    @Override
    public String toString() {
        return String.format("£%d.%02d", pence / 100, pence % 100);
    }
}
