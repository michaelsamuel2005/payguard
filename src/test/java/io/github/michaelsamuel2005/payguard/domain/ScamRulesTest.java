package io.github.michaelsamuel2005.payguard.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ScamRulesTest {

    // Thresholds pinned here so these tests check the rules themselves; ScamPolicyEvaluationTest checks the tuned ones.
    private final ScamRules rules = new ScamRules(ScamPolicy.DEFAULT.withThresholds(2, 4));

    private static PaymentContext context(long pounds, String reference, NameMatch match, boolean newPayee,
                                          List<Money> history, int recentNew) {
        return new PaymentContext(Money.ofPounds(pounds), reference, match, newPayee, history, recentNew);
    }

    private static List<Money> usual() {
        return List.of(Money.ofPounds(40), Money.ofPounds(60), Money.ofPounds(50));
    }

    @Test
    void a_usual_payment_to_a_known_payee_is_allowed_with_no_warning() {
        RiskAssessment r = rules.assess(context(55, "DINNER", NameMatch.MATCH, false, usual(), 0));
        assertThat(r.outcome()).isEqualTo(Outcome.ALLOW);
        assertThat(r.signals()).isEmpty();
        assertThat(r.warning()).isNull();
    }

    @Test
    void a_large_payment_to_a_new_payee_whose_name_does_not_match_is_held() {
        RiskAssessment r = rules.assess(context(4_000, "INVOICE 2231", NameMatch.NO_MATCH, true, usual(), 0));
        assertThat(r.signals()).containsExactly(Signal.NEW_PAYEE, Signal.NAME_NO_MATCH, Signal.UNUSUAL_AMOUNT);
        assertThat(r.score()).isEqualTo(6);
        assertThat(r.outcome()).isEqualTo(Outcome.HOLD);
        assertThat(r.warning()).isEqualTo(WarningCode.NAME_MISMATCH);
    }

    @Test
    void the_most_specific_warning_wins() {
        RiskAssessment r = rules.assess(context(30, "SAFE ACCOUNT", NameMatch.NO_MATCH, true, usual(), 0));
        assertThat(r.warning()).isEqualTo(WarningCode.SCAM_LANGUAGE);
        RiskAssessment large = rules.assess(context(900, "DEPOSIT", NameMatch.MATCH, true, usual(), 0));
        assertThat(large.signals()).containsExactly(Signal.NEW_PAYEE, Signal.UNUSUAL_AMOUNT);
        assertThat(large.warning()).isEqualTo(WarningCode.LARGE_NEW_PAYEE);
    }

    @Test
    void an_unchecked_name_is_never_a_silent_pass_whatever_the_thresholds() {
        ScamRules lenient = new ScamRules(ScamPolicy.DEFAULT.withThresholds(5, 9));
        RiskAssessment r = lenient.assess(context(20, "LUNCH", NameMatch.UNAVAILABLE, false, usual(), 0));
        assertThat(r.score()).isEqualTo(2);
        assertThat(r.outcome()).isEqualTo(Outcome.WARN);
        assertThat(r.warning()).isEqualTo(WarningCode.NAME_CHECK_UNAVAILABLE);
    }

    @Test
    void unusual_means_large_against_the_customers_own_median_and_above_a_floor() {
        // the median is 50, so anything over 5 x 50 = 250 pounds is unusual
        assertThat(rules.isUnusual(Money.ofPounds(251), usual())).isTrue();
        assertThat(rules.isUnusual(Money.ofPounds(250), usual())).isFalse();
        // a customer who only ever pays a pound: 40 pounds is 40 times the median but below the floor
        assertThat(rules.isUnusual(Money.ofPounds(40), List.of(Money.ofPounds(1)))).isFalse();
        // no history: 1,000 pounds or more is unusual
        assertThat(rules.isUnusual(Money.ofPounds(999), List.of())).isFalse();
        assertThat(rules.isUnusual(Money.ofPounds(1_000), List.of())).isTrue();
    }

    @Test
    void the_median_of_an_even_count_is_the_mean_of_the_middle_two() {
        assertThat(ScamRules.median(List.of(Money.ofPence(10), Money.ofPence(40), Money.ofPence(20), Money.ofPence(30))))
                .isEqualTo(Money.ofPence(25));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SAFE ACCOUNT", "move to safe acc", "HMRC fine", "crypto", "BTC 2", "invest now"})
    void scam_phrases_are_found_as_whole_words(String reference) {
        assertThat(ScamRules.mentionsScamLanguage(reference)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"RENT", "SAFEWAY STORES", "BTCX", "INVESTOR CLUB"})
    void words_that_only_contain_a_phrase_do_not_count(String reference) {
        assertThat(ScamRules.mentionsScamLanguage(reference)).isFalse();
    }

    @Test
    void several_new_payees_in_a_short_time_is_a_signal_on_the_third() {
        assertThat(rules.assess(context(20, "GIFT", NameMatch.MATCH, true, usual(), 1)).signals())
                .doesNotContain(Signal.RAPID_NEW_PAYEES);
        assertThat(rules.assess(context(20, "GIFT", NameMatch.MATCH, true, usual(), 2)).signals())
                .contains(Signal.RAPID_NEW_PAYEES);
        assertThat(rules.assess(context(20, "GIFT", NameMatch.MATCH, false, usual(), 9)).signals())
                .doesNotContain(Signal.RAPID_NEW_PAYEES);
    }

    @Test
    void a_first_payment_to_a_matching_new_payee_is_allowed_alone() {
        RiskAssessment r = rules.assess(context(30, "BIRTHDAY", NameMatch.MATCH, true, usual(), 0));
        assertThat(r.signals()).containsExactly(Signal.NEW_PAYEE);
        assertThat(r.outcome()).isEqualTo(Outcome.ALLOW);
    }
}
