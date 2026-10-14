package io.github.michaelsamuel2005.payguard.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentTest {

    private static final Instant T0 = Instant.parse("2026-10-14T09:00:00Z");
    private static final CustomerId ALICE = new CustomerId("alice");

    private static Payment payment(Outcome outcome, WarningCode warning) {
        RiskAssessment risk = new RiskAssessment(outcome, outcome == Outcome.ALLOW ? 0 : 3,
                outcome == Outcome.ALLOW ? List.of() : List.of(Signal.NAME_NO_MATCH), warning);
        return Payment.initiate(new PaymentId(UUID.randomUUID()), ALICE, new PayeeId(UUID.randomUUID()),
                Money.ofPounds(100), new PaymentReference("RENT"), risk, T0);
    }

    @Test
    void the_outcome_sets_the_first_status() {
        assertThat(payment(Outcome.ALLOW, null).status()).isEqualTo(PaymentStatus.RELEASED);
        assertThat(payment(Outcome.WARN, WarningCode.NAME_MISMATCH).status())
                .isEqualTo(PaymentStatus.AWAITING_CONFIRMATION);
        assertThat(payment(Outcome.HOLD, WarningCode.NAME_MISMATCH).status()).isEqualTo(PaymentStatus.HELD);
    }

    @Test
    void a_warned_payment_is_released_only_by_acknowledging_the_warning_shown() {
        Payment p = payment(Outcome.WARN, WarningCode.NAME_MISMATCH);
        assertThatThrownBy(() -> p.confirm(ALICE, WarningCode.NEW_PAYEE, T0))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("NAME_MISMATCH");
        AuditEvent e = p.confirm(ALICE, WarningCode.NAME_MISMATCH, T0.plusSeconds(30));
        assertThat(p.status()).isEqualTo(PaymentStatus.RELEASED);
        assertThat(e.from()).isEqualTo(PaymentStatus.AWAITING_CONFIRMATION);
        assertThat(e.to()).isEqualTo(PaymentStatus.RELEASED);
        assertThat(e.actor()).isEqualTo("alice");
        assertThat(p.updatedAt()).isEqualTo(T0.plusSeconds(30));
    }

    @Test
    void another_customer_cannot_touch_the_payment_or_learn_it_exists() {
        Payment p = payment(Outcome.WARN, WarningCode.NAME_MISMATCH);
        CustomerId mallory = new CustomerId("mallory");
        assertThatThrownBy(() -> p.confirm(mallory, WarningCode.NAME_MISMATCH, T0))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> p.cancel(mallory, T0)).isInstanceOf(NotFoundException.class);
        assertThat(p.status()).isEqualTo(PaymentStatus.AWAITING_CONFIRMATION);
    }

    @Test
    void a_reviewer_decides_a_held_payment_with_a_reason_but_never_their_own() {
        Payment p = payment(Outcome.HOLD, WarningCode.NAME_MISMATCH);
        assertThatThrownBy(() -> p.approve("alice", "looks fine", T0)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> p.approve("rita", "  ", T0)).isInstanceOf(InvalidRequestException.class);
        AuditEvent e = p.reject("rita", "customer confirmed a scam call", T0);
        assertThat(p.status()).isEqualTo(PaymentStatus.REJECTED);
        assertThat(e.detail()).isEqualTo("customer confirmed a scam call");
        assertThatThrownBy(() -> p.approve("rita", "changed my mind", T0))
                .isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void only_waiting_payments_can_be_cancelled() {
        Payment held = payment(Outcome.HOLD, WarningCode.NAME_MISMATCH);
        held.cancel(ALICE, T0);
        assertThat(held.status()).isEqualTo(PaymentStatus.CANCELLED);
        Payment released = payment(Outcome.ALLOW, null);
        assertThatThrownBy(() -> released.cancel(ALICE, T0)).isInstanceOf(InvalidTransitionException.class);
        assertThat(PaymentStatus.CANCELLED.isFinal()).isTrue();
        assertThat(PaymentStatus.HELD.isFinal()).isFalse();
    }

    @Test
    void a_zero_payment_is_refused() {
        RiskAssessment allow = new RiskAssessment(Outcome.ALLOW, 0, List.of(), null);
        assertThatThrownBy(() -> Payment.initiate(new PaymentId(UUID.randomUUID()), ALICE,
                new PayeeId(UUID.randomUUID()), Money.ZERO, new PaymentReference("X"), allow, T0))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void the_creation_event_records_the_decision() {
        Payment p = payment(Outcome.HOLD, WarningCode.NAME_MISMATCH);
        AuditEvent e = p.initiatedEvent();
        assertThat(e.from()).isNull();
        assertThat(e.to()).isEqualTo(PaymentStatus.HELD);
        assertThat(e.detail()).contains("outcome=HOLD").contains("NAME_NO_MATCH");
    }
}
