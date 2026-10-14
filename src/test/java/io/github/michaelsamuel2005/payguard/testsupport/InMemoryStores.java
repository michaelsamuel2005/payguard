package io.github.michaelsamuel2005.payguard.testsupport;

import io.github.michaelsamuel2005.payguard.application.AuditLog;
import io.github.michaelsamuel2005.payguard.application.DuplicateIdempotencyKeyException;
import io.github.michaelsamuel2005.payguard.application.DuplicatePayeeException;
import io.github.michaelsamuel2005.payguard.application.IdempotencyRecord;
import io.github.michaelsamuel2005.payguard.application.IdempotencyStore;
import io.github.michaelsamuel2005.payguard.application.PayeeRepository;
import io.github.michaelsamuel2005.payguard.application.PaymentRepository;
import io.github.michaelsamuel2005.payguard.domain.AuditEvent;
import io.github.michaelsamuel2005.payguard.domain.ConcurrentUpdateException;
import io.github.michaelsamuel2005.payguard.domain.CustomerId;
import io.github.michaelsamuel2005.payguard.domain.Money;
import io.github.michaelsamuel2005.payguard.domain.NameCheckResult;
import io.github.michaelsamuel2005.payguard.domain.Payee;
import io.github.michaelsamuel2005.payguard.domain.PayeeId;
import io.github.michaelsamuel2005.payguard.domain.Payment;
import io.github.michaelsamuel2005.payguard.domain.PaymentId;
import io.github.michaelsamuel2005.payguard.domain.PaymentStatus;
import io.github.michaelsamuel2005.payguard.domain.Signal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** In-memory versions of every repository, with the same contracts as the JDBC ones. */
public final class InMemoryStores {

    private InMemoryStores() {
    }

    public static final class Payees implements PayeeRepository, FakeTransactions.Participant {
        private Map<PayeeId, Payee> rows = new LinkedHashMap<>();

        @Override
        public void insert(Payee p) {
            boolean clash = rows.values().stream().anyMatch(o -> o.customer().equals(p.customer())
                    && o.sortCode().equals(p.sortCode()) && o.accountNumber().equals(p.accountNumber()));
            if (clash) {
                throw new DuplicatePayeeException();
            }
            rows.put(p.id(), p);
        }

        @Override
        public Optional<Payee> find(CustomerId customer, PayeeId id) {
            return Optional.ofNullable(rows.get(id)).filter(p -> p.customer().equals(customer));
        }

        @Override
        public List<Payee> list(CustomerId customer) {
            return rows.values().stream().filter(p -> p.customer().equals(customer)).toList();
        }

        @Override
        public void updateNameCheck(PayeeId id, NameCheckResult result) {
            rows.computeIfPresent(id, (k, p) -> p.withNameCheck(result));
        }

        @Override
        public Object snapshot() {
            return new LinkedHashMap<>(rows);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void restore(Object s) {
            rows = (Map<PayeeId, Payee>) s;
        }
    }

    /** Stores copies, so a payment changed in a service is not saved until update() succeeds. */
    public static final class Payments implements PaymentRepository, FakeTransactions.Participant {
        private Map<PaymentId, Payment> rows = new LinkedHashMap<>();

        @Override
        public void insert(Payment p) {
            if (rows.containsKey(p.id())) {
                throw new IllegalStateException("duplicate payment id");
            }
            rows.put(p.id(), copy(p, p.version()));
        }

        @Override
        public Optional<Payment> find(PaymentId id) {
            return Optional.ofNullable(rows.get(id)).map(p -> copy(p, p.version()));
        }

        @Override
        public void update(Payment p) {
            Payment stored = rows.get(p.id());
            if (stored == null || stored.version() != p.version()) {
                throw new ConcurrentUpdateException(p.id());
            }
            rows.put(p.id(), copy(p, p.version() + 1));
        }

        @Override
        public boolean hasReleasedPaymentTo(CustomerId customer, PayeeId payee) {
            return rows.values().stream().anyMatch(p -> p.customer().equals(customer) && p.payee().equals(payee)
                    && p.status() == PaymentStatus.RELEASED);
        }

        @Override
        public List<Money> releasedAmountsSince(CustomerId customer, Instant since) {
            return rows.values().stream()
                    .filter(p -> p.customer().equals(customer) && p.status() == PaymentStatus.RELEASED
                            && !p.createdAt().isBefore(since))
                    .sorted(Comparator.comparing(Payment::createdAt).reversed())
                    .limit(50).map(Payment::amount).toList();
        }

        @Override
        public int newPayeePaymentsSince(CustomerId customer, Instant since) {
            return (int) rows.values().stream().filter(p -> p.customer().equals(customer)
                    && !p.createdAt().isBefore(since) && p.risk().signals().contains(Signal.NEW_PAYEE)).count();
        }

        @Override
        public List<Payment> held(int limit) {
            return rows.values().stream().filter(p -> p.status() == PaymentStatus.HELD)
                    .sorted(Comparator.comparing(Payment::createdAt).thenComparing(p -> p.id().value()))
                    .limit(limit).map(p -> copy(p, p.version())).toList();
        }

        @Override
        public int countHeld() {
            return (int) rows.values().stream().filter(p -> p.status() == PaymentStatus.HELD).count();
        }

        public int size() {
            return rows.size();
        }

        private static Payment copy(Payment p, int version) {
            return Payment.restore(p.id(), p.customer(), p.payee(), p.amount(), p.reference(), p.risk(),
                    p.createdAt(), p.status(), p.updatedAt(), version);
        }

        @Override
        public Object snapshot() {
            return new LinkedHashMap<>(rows);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void restore(Object s) {
            rows = (Map<PaymentId, Payment>) s;
        }
    }

    public static final class Keys implements IdempotencyStore, FakeTransactions.Participant {
        private Map<String, IdempotencyRecord> rows = new LinkedHashMap<>();

        @Override
        public Optional<IdempotencyRecord> find(CustomerId customer, String key) {
            return Optional.ofNullable(rows.get(customer.value() + "|" + key));
        }

        @Override
        public void insert(IdempotencyRecord r) {
            if (rows.putIfAbsent(r.customer().value() + "|" + r.key(), r) != null) {
                throw new DuplicateIdempotencyKeyException();
            }
        }

        /** Simulates a concurrent request that committed this key just before ours. */
        public void sneakIn(IdempotencyRecord r) {
            rows.put(r.customer().value() + "|" + r.key(), r);
        }

        @Override
        public Object snapshot() {
            return new LinkedHashMap<>(rows);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void restore(Object s) {
            rows = (Map<String, IdempotencyRecord>) s;
        }
    }

    public static final class Audit implements AuditLog, FakeTransactions.Participant {
        private List<AuditEvent> rows = new ArrayList<>();

        @Override
        public void append(AuditEvent e) {
            rows.add(e);
        }

        @Override
        public List<AuditEvent> forPayment(PaymentId payment) {
            return rows.stream().filter(e -> e.paymentId().equals(payment)).toList();
        }

        public int size() {
            return rows.size();
        }

        @Override
        public Object snapshot() {
            return new ArrayList<>(rows);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void restore(Object s) {
            rows = (List<AuditEvent>) s;
        }
    }
}
