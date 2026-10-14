package io.github.michaelsamuel2005.payguard.evaluation;

import io.github.michaelsamuel2005.payguard.domain.Money;
import io.github.michaelsamuel2005.payguard.domain.NameMatch;
import io.github.michaelsamuel2005.payguard.domain.PaymentContext;
import io.github.michaelsamuel2005.payguard.domain.ScamPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Simulated payments with planted scams. Seeded and computed with {@link StrictMath}, so every run on every JVM
 * produces exactly the same payments. The proportions below are assumptions made to exercise the rules, not
 * measurements of real fraud; they are printed in docs/SCORECARD.md.
 */
final class ScamSimulation {

    enum Kind { GENUINE, IMPERSONATION, INVESTMENT, PURCHASE, INVOICE_REDIRECTION }

    record SimPayment(Kind kind, PaymentContext context) {
        boolean scam() {
            return kind != Kind.GENUINE;
        }
    }

    static final long SEED = 20261014L;
    static final int CUSTOMERS = 5_000;
    static final int GENUINE = 50_000;
    static final int SCAMS = 1_000;
    static final double UNAVAILABLE_RATE = 0.005;

    static final String[] GENUINE_REFS = {"RENT", "DINNER", "INVOICE 1042", "SAVINGS", "BIRTHDAY", "UTILITIES",
        "GROCERIES", "HOLIDAY", "LOAN REPAY", "CLEANER", "SCHOOL TRIP", "TAXI", "GIFT", "FOOTBALL SUBS", "DEPOSIT"};
    /** Genuine references that happen to use scam words: the source of SCAM_LANGUAGE false alarms. */
    static final String[] GENUINE_RISKY_REFS = {"ISA INVESTMENT", "INVEST CLUB", "HMRC SELF ASSESS"};
    static final String[] IMPERSONATION_REFS = {"SAFE ACCOUNT", "HMRC", "SECURE ACCOUNT", "SAFE ACC 1"};
    static final String[] INVESTMENT_REFS = {"CRYPTO", "BITCOIN", "INVESTMENT", "BTC WALLET"};
    static final String[] PLAIN_REFS = {"PAYMENT", "TRANSFER", "TOP UP", "DEPOSIT"};
    static final String[] PURCHASE_REFS = {"PS5", "TICKETS", "PUPPY DEPOSIT", "CAR DEPOSIT", "SOFA"};
    static final String[] INVOICE_REFS = {"INVOICE 2231", "INV 7781", "BUILDER STAGE 2"};

    private ScamSimulation() {
    }

    static List<SimPayment> generate() {
        Random rnd = new Random(SEED);
        double[] median = new double[CUSTOMERS];
        List<List<Money>> history = new ArrayList<>();
        for (int c = 0; c < CUSTOMERS; c++) {
            median[c] = Math.min(2_000, Math.max(5, lognormal(rnd, StrictMath.log(60), 0.8)));
            int k = rnd.nextDouble() < 0.10 ? 0 : 1 + rnd.nextInt(20);
            List<Money> h = new ArrayList<>();
            for (int i = 0; i < k; i++) {
                h.add(pence(median[c] * lognormal(rnd, 0, 0.5)));
            }
            history.add(List.copyOf(h));
        }
        List<SimPayment> out = new ArrayList<>(GENUINE + SCAMS);
        for (int i = 0; i < GENUINE; i++) {
            int c = rnd.nextInt(CUSTOMERS);
            out.add(genuine(rnd, median[c], history.get(c)));
        }
        for (int i = 0; i < SCAMS; i++) {
            int c = rnd.nextInt(CUSTOMERS);
            out.add(scam(rnd, median[c], history.get(c)));
        }
        return out;
    }

    private static SimPayment genuine(Random rnd, double m, List<Money> history) {
        boolean newPayee = rnd.nextDouble() < 0.20;
        NameMatch name = newPayee ? pick(rnd, 0.88, 0.09) : NameMatch.MATCH;
        double pounds = newPayee && rnd.nextDouble() < 0.10
                ? m * uniform(rnd, 5, 40)                       // a genuine one-off: a deposit, a car, a builder
                : m * lognormal(rnd, 0, newPayee ? 0.6 : 0.5);
        String ref = rnd.nextDouble() < 0.01 ? any(rnd, GENUINE_RISKY_REFS) : any(rnd, GENUINE_REFS);
        int recentNew = 0;
        if (newPayee) {
            double r = rnd.nextDouble();
            recentNew = r < 0.02 ? 2 : r < 0.12 ? 1 : 0;
        }
        return payment(rnd, Kind.GENUINE, pounds, ref, name, newPayee, history, recentNew);
    }

    private static SimPayment scam(Random rnd, double m, List<Money> history) {
        double k = rnd.nextDouble();
        if (k < 0.30) {
            NameMatch name = pickNoMatchFirst(rnd, 0.70, 0.10);
            String ref = rnd.nextDouble() < 0.60 ? any(rnd, IMPERSONATION_REFS) : any(rnd, PLAIN_REFS);
            int recentNew = rnd.nextDouble() < 0.40 ? 2 + rnd.nextInt(3) : 0;
            return payment(rnd, Kind.IMPERSONATION, m * uniform(rnd, 5, 60), ref, name, true, history, recentNew);
        }
        if (k < 0.55) {
            boolean newPayee = rnd.nextDouble() < 0.90;
            NameMatch name = pickNoMatchFirst(rnd, 0.40, 0.10);
            String ref = rnd.nextDouble() < 0.50 ? any(rnd, INVESTMENT_REFS) : any(rnd, PLAIN_REFS);
            int recentNew = newPayee && rnd.nextDouble() < 0.15 ? 2 : 0;
            return payment(rnd, Kind.INVESTMENT, m * uniform(rnd, 3, 50), ref, name, newPayee, history, recentNew);
        }
        if (k < 0.85) {
            NameMatch name = pick(rnd, 0.60, 0.15);
            int recentNew = rnd.nextDouble() < 0.05 ? 2 : 0;
            return payment(rnd, Kind.PURCHASE, m * lognormal(rnd, 0.5, 0.6), any(rnd, PURCHASE_REFS), name, true,
                    history, recentNew);
        }
        NameMatch name = pickNoMatchFirst(rnd, 0.60, 0.10);
        return payment(rnd, Kind.INVOICE_REDIRECTION, m * uniform(rnd, 5, 30), any(rnd, INVOICE_REFS), name, true,
                history, 0);
    }

    private static SimPayment payment(Random rnd, Kind kind, double pounds, String ref, NameMatch name,
                                      boolean newPayee, List<Money> history, int recentNew) {
        NameMatch checked = rnd.nextDouble() < UNAVAILABLE_RATE ? NameMatch.UNAVAILABLE : name;
        return new SimPayment(kind, new PaymentContext(pence(pounds), ref, checked, newPayee, history, recentNew));
    }

    /** MATCH with probability pMatch, CLOSE_MATCH with pClose, otherwise NO_MATCH. */
    private static NameMatch pick(Random rnd, double pMatch, double pClose) {
        double u = rnd.nextDouble();
        return u < pMatch ? NameMatch.MATCH : u < pMatch + pClose ? NameMatch.CLOSE_MATCH : NameMatch.NO_MATCH;
    }

    /** NO_MATCH with probability pNo, CLOSE_MATCH with pClose, otherwise MATCH. */
    private static NameMatch pickNoMatchFirst(Random rnd, double pNo, double pClose) {
        double u = rnd.nextDouble();
        return u < pNo ? NameMatch.NO_MATCH : u < pNo + pClose ? NameMatch.CLOSE_MATCH : NameMatch.MATCH;
    }

    private static Money pence(double pounds) {
        long p = StrictMath.round(pounds * 100.0);
        return Money.ofPence(Math.min(ScamPolicy.DEFAULT.maxPayment().pence(), Math.max(100, p)));
    }

    private static double lognormal(Random rnd, double mu, double sigma) {
        return StrictMath.exp(mu + sigma * rnd.nextGaussian());
    }

    private static double uniform(Random rnd, double lo, double hi) {
        return lo + (hi - lo) * rnd.nextDouble();
    }

    private static String any(Random rnd, String[] options) {
        return options[rnd.nextInt(options.length)];
    }
}
