package io.github.michaelsamuel2005.payguard.application;

import static io.github.michaelsamuel2005.payguard.application.ServiceFixture.ALICE;
import static io.github.michaelsamuel2005.payguard.application.ServiceFixture.key;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.michaelsamuel2005.payguard.domain.ConcurrentUpdateException;
import io.github.michaelsamuel2005.payguard.domain.ForbiddenException;
import io.github.michaelsamuel2005.payguard.domain.InvalidRequestException;
import io.github.michaelsamuel2005.payguard.domain.NotFoundException;
import io.github.michaelsamuel2005.payguard.domain.PayeeId;
import io.github.michaelsamuel2005.payguard.domain.Payment;
import io.github.michaelsamuel2005.payguard.domain.PaymentId;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReviewServiceTest {

    private final ServiceFixture f = new ServiceFixture();

    private PaymentId held() {
        PayeeId wrong = f.payee(ALICE, "Mike Jones " + UUID.randomUUID().toString().substring(0, 4), "20-30-40",
                String.format("%08d", Math.abs(UUID.randomUUID().hashCode()) % 100_000_000));
        return PaymentId.parse(f.paymentService.initiate(ALICE, key(), wrong, 400_000, "INVOICE").payment().id());
    }

    @Test
    void the_queue_is_oldest_first_and_capped() {
        PaymentId first = held();
        f.clock.advance(Duration.ofMinutes(5));
        PaymentId second = held();
        assertThat(f.reviewService.queue(10)).extracting(PaymentView::id)
                .containsExactly(first.toString(), second.toString());
        assertThat(f.reviewService.queue(1)).hasSize(1);
        assertThat(f.reviewService.queue(0)).hasSize(1);
        assertThat(f.reviewService.heldCount()).isEqualTo(2);
    }

    @Test
    void a_decision_needs_a_known_verb_and_a_reason_and_cannot_be_the_customers_own() {
        PaymentId id = held();
        assertThatThrownBy(() -> f.reviewService.decide("rita", id, "maybe", "x"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> f.reviewService.decide("rita", id, "REJECT", ""))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> f.reviewService.decide("alice", id, "APPROVE", "mine"))
                .isInstanceOf(ForbiddenException.class);
        assertThat(f.reviewService.decide("rita", id, "reject", "mule account reported").status())
                .isEqualTo("REJECTED");
    }

    @Test
    void two_reviewers_cannot_both_decide_the_same_payment() {
        PaymentId id = held();
        Payment staleCopy = f.payments.find(id).orElseThrow();
        f.reviewService.decide("rita", id, "APPROVE", "verified by phone");
        staleCopy.reject("sam", "looks like a mule account", f.clock.instant());
        assertThatThrownBy(() -> f.payments.update(staleCopy)).isInstanceOf(ConcurrentUpdateException.class);
    }

    @Test
    void an_unknown_payment_is_not_found() {
        assertThatThrownBy(() -> f.reviewService.decide("rita", new PaymentId(UUID.randomUUID()), "APPROVE", "x"))
                .isInstanceOf(NotFoundException.class);
    }
}
