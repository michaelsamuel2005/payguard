package io.github.michaelsamuel2005.payguard.adapters.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.michaelsamuel2005.payguard.application.DuplicateIdempotencyKeyException;
import io.github.michaelsamuel2005.payguard.application.DuplicatePayeeException;
import io.github.michaelsamuel2005.payguard.application.IdempotencyRecord;
import io.github.michaelsamuel2005.payguard.domain.AccountNumber;
import io.github.michaelsamuel2005.payguard.domain.AuditEvent;
import io.github.michaelsamuel2005.payguard.domain.ConcurrentUpdateException;
import io.github.michaelsamuel2005.payguard.domain.CustomerId;
import io.github.michaelsamuel2005.payguard.domain.Money;
import io.github.michaelsamuel2005.payguard.domain.NameCheckResult;
import io.github.michaelsamuel2005.payguard.domain.NameMatch;
import io.github.michaelsamuel2005.payguard.domain.Outcome;
import io.github.michaelsamuel2005.payguard.domain.Payee;
import io.github.michaelsamuel2005.payguard.domain.PayeeId;
import io.github.michaelsamuel2005.payguard.domain.Payment;
import io.github.michaelsamuel2005.payguard.domain.PaymentId;
import io.github.michaelsamuel2005.payguard.domain.PaymentReference;
import io.github.michaelsamuel2005.payguard.domain.PaymentStatus;
import io.github.michaelsamuel2005.payguard.domain.RiskAssessment;
import io.github.michaelsamuel2005.payguard.domain.Signal;
import io.github.michaelsamuel2005.payguard.domain.SortCode;
import io.github.michaelsamuel2005.payguard.domain.WarningCode;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class JdbcRepositoriesTest {

    private static final Instant T0 = Instant.parse("2026-10-14T09:00:00.123456Z");
    private static final CustomerId ALICE = new CustomerId("alice");

    private static DataSource ds;
    private static JdbcTransactionRunner tx;
    private static JdbcPayeeRepository payees;
    private static JdbcPaymentRepository payments;
    private static JdbcIdempotencyStore keys;
    private static JdbcAuditLog audit;

    @BeforeAll
    static void database() {
        ds = TestDatabase.freshSchema();
        tx = new JdbcTransactionRunner(ds);
        payees = new JdbcPayeeRepository(tx);
        payments = new JdbcPaymentRepository(tx);
        keys = new JdbcIdempotencyStore(tx);
        audit = new JdbcAuditLog(tx);
    }

    private static Payee newPayee(CustomerId customer, String account, NameCheckResult check) {
        Payee p = new Payee(new PayeeId(UUID.randomUUID()), customer, "John Smith", new SortCode("12-34-56"),
                new AccountNumber(account), check, T0);
        tx.execute(() -> payees.insert(p));
        return p;
    }

    private static Payment newPayment(Payee payee, Outcome outcome, Instant at, long pence) {
        RiskAssessment risk = outcome == Outcome.ALLOW
                ? new RiskAssessment(Outcome.ALLOW, 1, List.of(Signal.NEW_PAYEE), null)
                : new RiskAssessment(outcome, 6, List.of(Signal.NEW_PAYEE, Signal.NAME_NO_MATCH, Signal.UNUSUAL_AMOUNT),
                        WarningCode.NAME_MISMATCH);
        Payment p = Payment.initiate(new PaymentId(UUID.randomUUID()), payee.customer(), payee.id(),
                Money.ofPence(pence), new PaymentReference("RENT"), risk, at);
        tx.execute(() -> payments.insert(p));
        return p;
    }

    @Test
    void a_payee_round_trips_with_its_name_check() {
        Payee p = newPayee(ALICE, "10000001", NameCheckResult.closeMatch("JOHN SMITH"));
        Payee back = tx.inTransaction(() -> payees.find(ALICE, p.id())).orElseThrow();
        assertThat(back).isEqualTo(p);
        assertThat(tx.inTransaction(() -> payees.find(new CustomerId("bob"), p.id()))).isEmpty();
        tx.execute(() -> payees.updateNameCheck(p.id(), NameCheckResult.of(NameMatch.MATCH)));
        assertThat(tx.inTransaction(() -> payees.find(ALICE, p.id())).orElseThrow().nameCheck())
                .isEqualTo(NameCheckResult.of(NameMatch.MATCH));
    }

    @Test
    void the_same_account_twice_for_one_customer_is_a_duplicate() {
        newPayee(ALICE, "10000002", NameCheckResult.of(NameMatch.MATCH));
        assertThatThrownBy(() -> newPayee(ALICE, "10000002", NameCheckResult.of(NameMatch.MATCH)))
                .isInstanceOf(DuplicatePayeeException.class);
        newPayee(new CustomerId("bob"), "10000002", NameCheckResult.of(NameMatch.MATCH));
    }

    @Test
    void a_payment_round_trips_with_its_signals_and_warning() {
        Payee payee = newPayee(ALICE, "10000003", NameCheckResult.of(NameMatch.NO_MATCH));
        Payment p = newPayment(payee, Outcome.HOLD, T0, 400_000);
        Payment back = tx.inTransaction(() -> payments.find(p.id())).orElseThrow();
        assertThat(back.status()).isEqualTo(PaymentStatus.HELD);
        assertThat(back.risk()).isEqualTo(p.risk());
        assertThat(back.amount()).isEqualTo(Money.ofPence(400_000));
        assertThat(back.createdAt()).isEqualTo(T0.truncatedTo(ChronoUnit.MICROS));
        assertThat(back.version()).isZero();
    }

    @Test
    void an_update_from_a_stale_read_is_refused() {
        Payee payee = newPayee(ALICE, "10000004", NameCheckResult.of(NameMatch.NO_MATCH));
        Payment p = newPayment(payee, Outcome.HOLD, T0, 400_000);
        Payment first = tx.inTransaction(() -> payments.find(p.id())).orElseThrow();
        Payment second = tx.inTransaction(() -> payments.find(p.id())).orElseThrow();
        first.approve("rita", "verified", T0.plusSeconds(60));
        tx.execute(() -> payments.update(first));
        second.reject("sam", "mule", T0.plusSeconds(61));
        assertThatThrownBy(() -> tx.execute(() -> payments.update(second)))
                .isInstanceOf(ConcurrentUpdateException.class);
        Payment stored = tx.inTransaction(() -> payments.find(p.id())).orElseThrow();
        assertThat(stored.status()).isEqualTo(PaymentStatus.RELEASED);
        assertThat(stored.version()).isEqualTo(1);
    }

    @Test
    void history_queries_see_only_what_they_should() {
        CustomerId carol = new CustomerId("carol-" + UUID.randomUUID());
        Payee payee = newPayee(carol, "10000005", NameCheckResult.of(NameMatch.MATCH));
        newPayment(payee, Outcome.ALLOW, T0.minus(100, ChronoUnit.DAYS), 9_900);
        newPayment(payee, Outcome.ALLOW, T0.minus(1, ChronoUnit.DAYS), 1_000);
        newPayment(payee, Outcome.HOLD, T0.minus(10, ChronoUnit.MINUTES), 500_000);
        tx.execute(() -> {
            assertThat(payments.hasReleasedPaymentTo(carol, payee.id())).isTrue();
            assertThat(payments.releasedAmountsSince(carol, T0.minus(90, ChronoUnit.DAYS)))
                    .containsExactly(Money.ofPence(1_000));
            assertThat(payments.newPayeePaymentsSince(carol, T0.minus(1, ChronoUnit.HOURS))).isEqualTo(1);
            assertThat(payments.hasReleasedPaymentTo(ALICE, payee.id())).isFalse();
        });
    }

    @Test
    void the_review_queue_is_oldest_first() {
        Payee payee = newPayee(ALICE, "10000006", NameCheckResult.of(NameMatch.NO_MATCH));
        Payment later = newPayment(payee, Outcome.HOLD, T0.plusSeconds(10), 400_000);
        Payment earlier = newPayment(payee, Outcome.HOLD, T0.minusSeconds(10), 400_000);
        List<PaymentId> queue = tx.inTransaction(() -> payments.held(200)).stream().map(Payment::id).toList();
        assertThat(queue.indexOf(earlier.id())).isLessThan(queue.indexOf(later.id()));
        assertThat(tx.inTransaction(payments::countHeld)).isEqualTo(queue.size());
    }

    @Test
    void an_idempotency_key_can_be_stored_once_per_customer() {
        Payee payee = newPayee(ALICE, "10000007", NameCheckResult.of(NameMatch.MATCH));
        Payment p = newPayment(payee, Outcome.ALLOW, T0, 1_000);
        IdempotencyRecord r = new IdempotencyRecord(ALICE, "key-0000001", "a".repeat(64), p.id(), T0);
        tx.execute(() -> keys.insert(r));
        assertThatThrownBy(() -> tx.execute(() -> keys.insert(r)))
                .isInstanceOf(DuplicateIdempotencyKeyException.class);
        assertThat(tx.inTransaction(() -> keys.find(ALICE, "key-0000001"))).hasValueSatisfying(
                found -> assertThat(found.paymentId()).isEqualTo(p.id()));
        assertThat(tx.inTransaction(() -> keys.find(new CustomerId("bob"), "key-0000001"))).isEmpty();
    }

    @Test
    void the_audit_trail_is_append_only_in_the_database_itself() throws SQLException {
        Payee payee = newPayee(ALICE, "10000008", NameCheckResult.of(NameMatch.MATCH));
        Payment p = newPayment(payee, Outcome.ALLOW, T0, 1_000);
        AuditEvent e = p.initiatedEvent();
        tx.execute(() -> audit.append(e));
        assertThat(tx.inTransaction(() -> audit.forPayment(p.id()))).containsExactly(
                new AuditEvent(e.paymentId(), e.actor(), e.action(), e.from(), e.to(), e.detail(),
                        e.at().truncatedTo(ChronoUnit.MICROS)));
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            assertThatThrownBy(() -> s.executeUpdate("update audit_events set detail = 'edited'"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("append-only");
            assertThatThrownBy(() -> s.executeUpdate("delete from audit_events"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("append-only");
            assertThatThrownBy(() -> s.executeUpdate("truncate audit_events"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("append-only");
        }
    }

    @Test
    void a_failure_inside_a_transaction_rolls_everything_back() {
        Payee payee = newPayee(ALICE, "10000009", NameCheckResult.of(NameMatch.MATCH));
        PaymentId id = new PaymentId(UUID.randomUUID());
        assertThatThrownBy(() -> tx.execute(() -> {
            payments.insert(Payment.initiate(id, ALICE, payee.id(), Money.ofPence(100), new PaymentReference("X"),
                    new RiskAssessment(Outcome.ALLOW, 0, List.of(), null), T0));
            throw new IllegalStateException("boom");
        })).hasMessage("boom");
        assertThat(tx.inTransaction(() -> payments.find(id))).isEmpty();
    }

    @Test
    void repositories_refuse_to_run_outside_a_transaction() {
        assertThatThrownBy(() -> payments.countHeld()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inside TransactionRunner");
    }

    @Test
    void the_schema_refuses_rows_the_domain_would_never_write() throws SQLException {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            assertThatThrownBy(() -> s.executeUpdate("insert into payees values (gen_random_uuid(), 'x', 'n', "
                    + "'12345', '12345678', 'MATCH', null, now())")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> s.executeUpdate("insert into payees values (gen_random_uuid(), 'x', 'n', "
                    + "'123456', '12345678', 'NO_MATCH', 'JOHN SMITH', now())")).isInstanceOf(SQLException.class);
        }
    }
}
