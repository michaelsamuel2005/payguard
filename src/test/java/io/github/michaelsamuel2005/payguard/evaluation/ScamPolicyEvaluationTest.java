package io.github.michaelsamuel2005.payguard.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.michaelsamuel2005.payguard.domain.Outcome;
import io.github.michaelsamuel2005.payguard.domain.RiskAssessment;
import io.github.michaelsamuel2005.payguard.domain.ScamPolicy;
import io.github.michaelsamuel2005.payguard.domain.ScamRules;
import io.github.michaelsamuel2005.payguard.domain.Signal;
import io.github.michaelsamuel2005.payguard.evaluation.ScamSimulation.Kind;
import io.github.michaelsamuel2005.payguard.evaluation.ScamSimulation.SimPayment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Applies the rule in docs/adr/0005-thresholds-by-a-written-rule.md to simulated payments, writes
 * docs/SCORECARD.md, and fails if ScamPolicy.DEFAULT is not the rule's choice or the committed scorecard is stale.
 *
 * <p>Regenerate the scorecard after an intended change with {@code PAYGUARD_WRITE_SCORECARD=1}.
 */
class ScamPolicyEvaluationTest {

    static final double MAX_GENUINE_HELD = 0.01;
    static final double MAX_GENUINE_INTERRUPTED = 0.10;
    static final Path SCORECARD = Path.of("docs/SCORECARD.md");

    record Scored(SimPayment payment, int score, boolean unchecked) {
        Outcome outcome(int warnAt, int holdAt) {
            if (score >= holdAt) {
                return Outcome.HOLD;
            }
            return score >= warnAt || unchecked ? Outcome.WARN : Outcome.ALLOW;
        }
    }

    record Pair(int warnAt, int holdAt, int genuine, int genuineHeld, int genuineInterrupted, int scams,
                int scamsHeld, int scamsInterrupted) {
        boolean meetsCaps() {
            return genuineHeld <= MAX_GENUINE_HELD * genuine && genuineInterrupted <= MAX_GENUINE_INTERRUPTED * genuine;
        }
    }

    @Test
    void the_default_thresholds_are_the_ones_the_written_rule_picks() throws IOException {
        List<SimPayment> payments = ScamSimulation.generate();
        ScamRules scorer = new ScamRules(ScamPolicy.DEFAULT);
        List<Scored> scored = payments.stream().map(p -> {
            RiskAssessment r = scorer.assess(p.context());
            return new Scored(p, r.score(), r.signals().contains(Signal.NAME_CHECK_UNAVAILABLE));
        }).toList();

        List<Pair> pairs = new ArrayList<>();
        for (int w = 1; w <= 10; w++) {
            for (int h = w + 1; h <= 11; h++) {
                pairs.add(evaluate(scored, w, h));
            }
        }
        Pair chosen = pairs.stream().filter(Pair::meetsCaps)
                .min(Comparator.comparingInt((Pair p) -> -p.scamsHeld())
                        .thenComparingInt(p -> -p.scamsInterrupted())
                        .thenComparingInt(Pair::genuineInterrupted)
                        .thenComparingInt(Pair::warnAt)
                        .thenComparingInt(Pair::holdAt))
                .orElseThrow(() -> new AssertionError("no threshold pair meets the caps"));

        // The shortcut above must agree with the production rules, payment by payment.
        ScamRules production = new ScamRules(ScamPolicy.DEFAULT.withThresholds(chosen.warnAt(), chosen.holdAt()));
        for (Scored s : scored) {
            assertThat(production.assess(s.payment().context()).outcome())
                    .isEqualTo(s.outcome(chosen.warnAt(), chosen.holdAt()));
        }

        String card = scorecard(scored, pairs, chosen);
        if (System.getenv("PAYGUARD_WRITE_SCORECARD") != null) {
            Files.writeString(SCORECARD, card, StandardCharsets.UTF_8);
        }
        assertThat(ScamPolicy.DEFAULT.warnAt()).as("ScamPolicy.DEFAULT.warnAt").isEqualTo(chosen.warnAt());
        assertThat(ScamPolicy.DEFAULT.holdAt()).as("ScamPolicy.DEFAULT.holdAt").isEqualTo(chosen.holdAt());
        assertThat(Files.readString(SCORECARD, StandardCharsets.UTF_8))
                .as("docs/SCORECARD.md is stale: rerun with PAYGUARD_WRITE_SCORECARD=1").isEqualTo(card);
    }

    private static Pair evaluate(List<Scored> scored, int w, int h) {
        int genuine = 0;
        int gHeld = 0;
        int gInt = 0;
        int scams = 0;
        int sHeld = 0;
        int sInt = 0;
        for (Scored s : scored) {
            Outcome o = s.outcome(w, h);
            if (s.payment().scam()) {
                scams++;
                sHeld += o == Outcome.HOLD ? 1 : 0;
                sInt += o == Outcome.ALLOW ? 0 : 1;
            } else {
                genuine++;
                gHeld += o == Outcome.HOLD ? 1 : 0;
                gInt += o == Outcome.ALLOW ? 0 : 1;
            }
        }
        return new Pair(w, h, genuine, gHeld, gInt, scams, sHeld, sInt);
    }

    private static String scorecard(List<Scored> scored, List<Pair> pairs, Pair chosen) {
        StringBuilder b = new StringBuilder();
        b.append("# Scorecard: warn and hold thresholds on simulated payments\n\n");
        b.append("Generated by `ScamPolicyEvaluationTest` (seed ").append(ScamSimulation.SEED)
                .append("); the test fails if this file or `ScamPolicy.DEFAULT` drifts from what the code produces.\n\n");
        b.append("**Simulated data only.** The proportions below are assumptions chosen to exercise the rules, not "
                + "measurements of real fraud. The thresholds are a starting point; real ones need real payments "
                + "labelled as scams or not, and the same rule applied to them.\n\n");
        b.append("## The payments\n\n");
        b.append(String.format(Locale.ROOT, "- %,d customers, each with their own usual payment size and up to 20 "
                + "released payments in the last 90 days (10%% have none).%n", ScamSimulation.CUSTOMERS));
        b.append(String.format(Locale.ROOT, "- %,d genuine payments: 80%% to existing payees with matching names; "
                + "20%% to new payees (names: 88%% match, 9%% close, 3%% no match), 10%% of those a large one-off; "
                + "1%% use references with scam words (for example ISA INVESTMENT); a few pay several new payees "
                + "within the hour.%n", ScamSimulation.GENUINE));
        b.append(String.format(Locale.ROOT, "- %,d planted scams: impersonation (30%%: safe-account and HMRC "
                + "stories, mostly mismatched names, large amounts, often in bursts), investment (25%%), purchase "
                + "(30%%: ordinary amounts, names often matching) and invoice redirection (15%%: new account details "
                + "for a real supplier).%n", ScamSimulation.SCAMS));
        b.append(String.format(Locale.ROOT, "- For %.1f%% of all payments the name check is unavailable.%n%n",
                ScamSimulation.UNAVAILABLE_RATE * 100));
        b.append("## The rule\n\n");
        b.append(String.format(Locale.ROOT, "From docs/adr/0005-thresholds-by-a-written-rule.md: hold at most %.0f%% "
                        + "of genuine payments; warn or hold at most %.0f%%; within those caps, hold the most scams, "
                        + "then interrupt the most scams, then interrupt the fewest genuine payments, then take the "
                        + "lowest thresholds.%n%n", MAX_GENUINE_HELD * 100, MAX_GENUINE_INTERRUPTED * 100));
        b.append(String.format(Locale.ROOT, "## Result: warn at %d, hold at %d%n%n", chosen.warnAt(),
                chosen.holdAt()));
        b.append("| | genuine | scams |\n|---|---:|---:|\n");
        b.append(row("held for review", chosen.genuineHeld(), chosen.genuine(), chosen.scamsHeld(), chosen.scams()));
        b.append(row("warned", chosen.genuineInterrupted() - chosen.genuineHeld(), chosen.genuine(),
                chosen.scamsInterrupted() - chosen.scamsHeld(), chosen.scams()));
        b.append(row("allowed", chosen.genuine() - chosen.genuineInterrupted(), chosen.genuine(),
                chosen.scams() - chosen.scamsInterrupted(), chosen.scams()));
        b.append("\n### By scam type\n\n| type | scams | held | warned | allowed |\n|---|---:|---:|---:|---:|\n");
        Map<Kind, int[]> byKind = new EnumMap<>(Kind.class);
        Map<String, Integer> missed = new LinkedHashMap<>();
        for (Scored s : scored) {
            if (!s.payment().scam()) {
                continue;
            }
            Outcome o = s.outcome(chosen.warnAt(), chosen.holdAt());
            int[] c = byKind.computeIfAbsent(s.payment().kind(), k -> new int[4]);
            c[0]++;
            c[o == Outcome.HOLD ? 1 : o == Outcome.WARN ? 2 : 3]++;
            if (o == Outcome.ALLOW) {
                String signals = new ScamRules(ScamPolicy.DEFAULT).assess(s.payment().context()).signals().stream()
                        .map(Signal::name).collect(Collectors.joining(" + "));
                missed.merge(s.payment().kind() + " with " + (signals.isEmpty() ? "no signal" : signals), 1, Integer::sum);
            }
        }
        for (Map.Entry<Kind, int[]> e : byKind.entrySet()) {
            int[] c = e.getValue();
            b.append(String.format(Locale.ROOT, "| %s | %,d | %s | %s | %s |%n", e.getKey().name().toLowerCase(Locale.ROOT)
                    .replace('_', ' '), c[0], pct(c[1], c[0]), pct(c[2], c[0]), pct(c[3], c[0])));
        }
        b.append("\n### What gets through\n\n");
        b.append("Allowed scams by type and the signals they carried (the rules cannot see what makes these scams):\n\n");
        missed.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .forEach(e -> b.append(String.format(Locale.ROOT, "- %,d: %s%n", e.getValue(),
                        e.getKey().toLowerCase(Locale.ROOT).replace('_', ' '))));
        b.append("\n## Every pair considered\n\n");
        b.append("| warn at | hold at | genuine held | genuine interrupted | scams held | scams interrupted | within caps |\n");
        b.append("|---:|---:|---:|---:|---:|---:|:---:|\n");
        for (Pair p : pairs) {
            b.append(String.format(Locale.ROOT, "| %d | %d | %s | %s | %s | %s | %s |%n", p.warnAt(), p.holdAt(),
                    pct(p.genuineHeld(), p.genuine()), pct(p.genuineInterrupted(), p.genuine()),
                    pct(p.scamsHeld(), p.scams()), pct(p.scamsInterrupted(), p.scams()),
                    p == chosen ? "**chosen**" : p.meetsCaps() ? "yes" : "no"));
        }
        return b.toString();
    }

    private static String row(String label, int g, int gTotal, int s, int sTotal) {
        return String.format(Locale.ROOT, "| %s | %,d (%s) | %,d (%s) |%n", label, g, pct(g, gTotal), s, pct(s, sTotal));
    }

    private static String pct(int n, int total) {
        return String.format(Locale.ROOT, "%.1f%%", 100.0 * n / total);
    }
}
