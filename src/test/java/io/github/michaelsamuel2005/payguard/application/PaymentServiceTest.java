package io.github.michaelsamuel2005.payguard.application;

import static io.github.michaelsamuel2005.payguard.application.ServiceFixture.ALICE;
import static io.github.michaelsamuel2005.payguard.application.ServiceFixture.BOB;
import static io.github.michaelsamuel2005.payguard.application.ServiceFixture.key;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.michaelsamuel2005.payguard.domain.CustomerId;
import io.github.michaelsamuel2005.payguard.domain.IdempotencyConflictException;
import io.github.michaelsamuel2005.payguard.domain.InvalidRequestException;
import io.github.michaelsamuel2005.payguard.domain.InvalidTransitionException;
import io.github.michaelsamuel2005.payguard.domain.NotFoundException;
import io.github.michaelsamuel2005.payguard.domain.PayeeId;
import io.github.michaelsamuel2005.payguard.domain.PaymentId;
import io.github.michaelsamuel2005.payguard.domain.ScamPolicy;
import io.github.michaelsamuel2005.payguard.domain.ScamRules;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentServiceTest {

    private final ServiceFixture f = new ServiceFixture();

    @Test
    void a_first_small_payment_to_a_matching_payee_is_released_and_audited() {
        PayeeId john = f.payee(ALICE, "John Smith", "12-34-56", "12345678");
        InitiateResult r = f.paymentService.initiate(ALICE, key(), john, 2_500, "DINNER");
        assertThat(r.replayed()).isFalse();
        assertThat(r.payment().status()).isEqualTo("RELEASED");
        assertThat(r.payment().signals()).containsExactly("NEW_PAYEE");
        assertThat(r.payment().warning()).isNull();
        assertThat(f.paymentService.audit(ALICE, PaymentId.parse(r.payment().id())))
                .extracting(AuditEntryView::action).containsExactly("INITIATED");
        assertThat(f.recorded).hasSize(1);
    }

    @Test
    void a_large_first_payment_to_a_mismatched_name_is_held_then_approved_by_a_reviewer() {
        PayeeId wrong = f.payee(ALICE, "Mike Jones", "20-30-40", "33334444");
        InitiateResult r = f.paymentService.initiate(ALICE, key(), wrong, 400_000, "INVOICE 77");
        assertThat(r.payment().status()).isEqualTo("HELD");
        assertThat(r.payment().warning().code()).isEqualTo("NAME_MISMATCH");
        assertThat(f.reviewService.queue(10)).extracting(PaymentView::id).containsExactly(r.payment().id());

        PaymentView approved = f.reviewService.decide("rita", PaymentId.parse(r.payment().id()), "approve",
                "called the customer on the number we hold; invoice confirmed");
        assertThat(approved.status()).isEqualTo("RELEASED");
        assertThat(f.reviewService.queue(10)).isEmpty();
        assertThat(f.reviewService.audit(PaymentId.parse(r.payment().id())))
                .extracting(AuditEntryView::action).containsExactly("INITIATED", "APPROVED");
    }

    @Test
    void a_warned_payment_waits_for_the_customer_to_acknowledge_the_warning() {
        PayeeId close = f.payee(ALICE, "Jon Smith", "12-34-56", "12345678");
        InitiateResult r = f.paymentService.initiate(ALICE, key(), close, 2_000, "GIFT");
        assertThat(r.payment().status()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(r.payment().warning().code()).isEqualTo("NAME_CLOSE_MATCH");
        PaymentId id = PaymentId.parse(r.payment().id());
        assertThatThrownBy(() -> f.paymentService.confirm(ALICE, id, "NEW_PAYEE"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> f.paymentService.confirm(ALICE, id, "NOT_A_CODE"))
                .isInstanceOf(InvalidRequestException.class);
        assertThat(f.paymentService.confirm(ALICE, id, "NAME_CLOSE_MATCH").status()).isEqualTo("RELEASED");
        assertThatThrownBy(() -> f.paymentService.cancel(ALICE, id)).isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void a_retry_with_the_same_key_returns_the_same_payment_and_pays_once() {
        PayeeId john = f.payee(ALICE, "John Smith", "12-34-56", "12345678");
        String k = key();
        InitiateResult first = f.paymentService.initiate(ALICE, k, john, 2_500, "DINNER");
        InitiateResult again = f.paymentService.initiate(ALICE, k, john, 2_500, " DINNER ");
        assertThat(again.replayed()).isTrue();
        assertThat(again.payment().id()).isEqualTo(first.payment().id());
        assertThat(f.payments.size()).isEqualTo(1);
        assertThat(f.recorded).hasSize(1);
    }

    @Test
    void the_same_key_with_a_different_body_is_refused() {
        PayeeId john = f.payee(ALICE, "John Smith", "12-34-56", "12345678");
        String k = key();
        f.paymentService.initiate(ALICE, k, john, 2_500, "DINNER");
        assertThatThrownBy(() -> f.paymentService.initiate(ALICE, k, john, 2_600, "DINNER"))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(f.payments.size()).isEqualTo(1);
    }

    @Test
    void keys_belong_to_a_customer() {
        PayeeId alicesJohn = f.payee(ALICE, "John Smith", "12-34-56", "12345678");
        PayeeId bobsJohn = f.payee(BOB, "John Smith", "12-34-56", "12345678");
        String k = key();
        f.paymentService.initiate(ALICE, k, alicesJohn, 2_500, "DINNER");
        assertThat(f.paymentService.initiate(BOB, k, bobsJohn, 2_500, "DINNER").replayed()).isFalse();
    }

    @Test
    void a_request_that_loses_the_race_for_its_key_answers_as_a_replay_and_leaves_no_second_payment() {
        PayeeId john = f.payee(ALICE, "John Smith", "12-34-56", "12345678");
        String k = key();
        InitiateResult winner = f.paymentService.initiate(ALICE, k, john, 2_500, "DINNER");
        // Make the store forget the key for the fast-path lookup only: the first find misses, the insert collides.
        InMemoryRaceStore race = new InMemoryRaceStore(f.keys);
        PaymentService racing = new PaymentService(f.tx, f.payees, f.payments, race, f.audit, f.nameCheck,
                new ScamRules(ScamPolicy.DEFAULT.withThresholds(2, 4)),
                f.recorded::add, f.clock, UUID::randomUUID);
        InitiateResult loser = racing.initiate(ALICE, k, john, 2_500, "DINNER");
        assertThat(loser.replayed()).isTrue();
        assertThat(loser.payment().id()).isEqualTo(winner.payment().id());
        assertThat(f.payments.size()).isEqualTo(1);
        assertThat(f.audit.size()).isEqualTo(1);
        assertThat(f.tx.rolledBack()).isEqualTo(1);
    }

    @Test
    void a_name_that_could_not_be_checked_is_checked_again_at_payment_time() {
        f.directory.setDown(true);
        PayeeId john = f.payee(ALICE, "John Smith", "12-34-56", "12345678");
        InitiateResult stillDown = f.paymentService.initiate(ALICE, key(), john, 2_000, "LUNCH");
        assertThat(stillDown.payment().status()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(stillDown.payment().warning().code()).isEqualTo("NAME_CHECK_UNAVAILABLE");

        f.directory.setDown(false);
        InitiateResult back = f.paymentService.initiate(ALICE, key(), john, 2_000, "LUNCH");
        assertThat(back.payment().signals()).doesNotContain("NAME_CHECK_UNAVAILABLE");
        assertThat(f.payeeService.list(ALICE).get(0).nameCheck()).isEqualTo("MATCH");
    }

    @Test
    void the_third_new_payee_within_the_hour_is_a_signal_and_it_resets_after_the_window() {
        PayeeId a = f.payee(ALICE, "John Smith", "12-34-56", "12345678");
        PayeeId b = f.payee(ALICE, "Priya Patel", "20-30-40", "33334444");
        PayeeId c = f.payee(ALICE, "Sam Hill", "99-99-99", "00000001");
        f.paymentService.initiate(ALICE, key(), a, 1_000, "ONE");
        f.paymentService.initiate(ALICE, key(), b, 1_000, "TWO");
        assertThat(f.paymentService.initiate(ALICE, key(), c, 1_000, "THREE").payment().signals())
                .contains("RAPID_NEW_PAYEES");
        f.clock.advance(Duration.ofHours(2));
        PayeeId d = f.payee(ALICE, "Ann Lee", "99-99-99", "00000002");
        assertThat(f.paymentService.initiate(ALICE, key(), d, 1_000, "FOUR").payment().signals())
                .doesNotContain("RAPID_NEW_PAYEES");
    }

    @Test
    void customers_only_ever_see_their_own_payees_and_payments() {
        PayeeId john = f.payee(ALICE, "John Smith", "12-34-56", "12345678");
        assertThatThrownBy(() -> f.paymentService.initiate(BOB, key(), john, 1_000, "X"))
                .isInstanceOf(NotFoundException.class);
        PaymentId id = PaymentId.parse(f.paymentService.initiate(ALICE, key(), john, 1_000, "X").payment().id());
        assertThatThrownBy(() -> f.paymentService.get(BOB, id)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> f.paymentService.audit(BOB, id)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void malformed_requests_are_refused() {
        PayeeId john = f.payee(ALICE, "John Smith", "12-34-56", "12345678");
        assertThatThrownBy(() -> f.paymentService.initiate(ALICE, "short", john, 1_000, "X"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> f.paymentService.initiate(ALICE, key(), john, 0, "X"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> f.paymentService.initiate(ALICE, key(), john, 2_500_001, "X"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> f.paymentService.initiate(ALICE, key(), john, 1_000, "<b>"))
                .isInstanceOf(InvalidRequestException.class);
        assertThat(f.payments.size()).isZero();
    }

    /** An idempotency store whose first lookup misses, as if another request committed the key in between. */
    private static final class InMemoryRaceStore implements IdempotencyStore {
        private final IdempotencyStore delegate;
        private boolean firstLookup = true;

        InMemoryRaceStore(IdempotencyStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<IdempotencyRecord> find(CustomerId c, String key) {
            if (firstLookup) {
                firstLookup = false;
                return Optional.empty();
            }
            return delegate.find(c, key);
        }

        @Override
        public void insert(IdempotencyRecord r) {
            delegate.insert(r);
        }
    }
}
