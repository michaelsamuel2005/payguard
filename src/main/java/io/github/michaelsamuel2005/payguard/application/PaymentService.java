package io.github.michaelsamuel2005.payguard.application;

import io.github.michaelsamuel2005.payguard.domain.AuditEvent;
import io.github.michaelsamuel2005.payguard.domain.CustomerId;
import io.github.michaelsamuel2005.payguard.domain.IdempotencyConflictException;
import io.github.michaelsamuel2005.payguard.domain.InvalidRequestException;
import io.github.michaelsamuel2005.payguard.domain.Money;
import io.github.michaelsamuel2005.payguard.domain.NameCheckResult;
import io.github.michaelsamuel2005.payguard.domain.NameMatch;
import io.github.michaelsamuel2005.payguard.domain.NotFoundException;
import io.github.michaelsamuel2005.payguard.domain.Payee;
import io.github.michaelsamuel2005.payguard.domain.PayeeId;
import io.github.michaelsamuel2005.payguard.domain.Payment;
import io.github.michaelsamuel2005.payguard.domain.PaymentContext;
import io.github.michaelsamuel2005.payguard.domain.PaymentId;
import io.github.michaelsamuel2005.payguard.domain.PaymentReference;
import io.github.michaelsamuel2005.payguard.domain.RiskAssessment;
import io.github.michaelsamuel2005.payguard.domain.ScamPolicy;
import io.github.michaelsamuel2005.payguard.domain.ScamRules;
import io.github.michaelsamuel2005.payguard.domain.WarningCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Starts, confirms, cancels and reads payments.
 *
 * <p>Every new payment needs an Idempotency-Key. A retry with the same key and the same body gets the original
 * payment back instead of paying twice; the same key with a different body is refused. Two identical requests
 * racing each other are settled by the database: one inserts the key, the other sees the duplicate and answers as
 * a replay of the first.
 */
public final class PaymentService {

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    private final TransactionRunner tx;
    private final PayeeRepository payees;
    private final PaymentRepository payments;
    private final IdempotencyStore idempotency;
    private final AuditLog audit;
    private final NameCheck nameCheck;
    private final ScamRules rules;
    private final DecisionRecorder recorder;
    private final Clock clock;
    private final Supplier<UUID> ids;

    public PaymentService(TransactionRunner tx, PayeeRepository payees, PaymentRepository payments,
                          IdempotencyStore idempotency, AuditLog audit, NameCheck nameCheck, ScamRules rules,
                          DecisionRecorder recorder, Clock clock, Supplier<UUID> ids) {
        this.tx = Objects.requireNonNull(tx, "tx");
        this.payees = Objects.requireNonNull(payees, "payees");
        this.payments = Objects.requireNonNull(payments, "payments");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.nameCheck = Objects.requireNonNull(nameCheck, "nameCheck");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    public InitiateResult initiate(CustomerId customer, String idempotencyKey, PayeeId payeeId, long amountPence,
                                   String reference) {
        String key = validKey(idempotencyKey);
        Money amount = validAmount(amountPence);
        PaymentReference ref = new PaymentReference(reference);
        String hash = requestHash(payeeId, amount, ref);

        Optional<InitiateResult> replay = tx.inTransaction(() -> replayOf(customer, key, hash));
        if (replay.isPresent()) {
            return replay.get();
        }

        Payee payee = tx.inTransaction(() -> payees.find(customer, payeeId))
                .orElseThrow(() -> new NotFoundException("no such payee"));
        NameMatch nameMatch = recheckIfUnavailable(payee);
        Instant now = clock.instant();
        ScamPolicy policy = rules.policy();
        try {
            Payment payment = tx.inTransaction(() -> {
                PaymentContext context = new PaymentContext(amount, ref.value(), nameMatch,
                        !payments.hasReleasedPaymentTo(customer, payeeId),
                        payments.releasedAmountsSince(customer, now.minus(policy.historyWindow())),
                        payments.newPayeePaymentsSince(customer, now.minus(policy.rapidWindow())));
                RiskAssessment risk = rules.assess(context);
                Payment p = Payment.initiate(new PaymentId(ids.get()), customer, payeeId, amount, ref, risk, now);
                payments.insert(p);
                idempotency.insert(new IdempotencyRecord(customer, key, hash, p.id(), now));
                audit.append(p.initiatedEvent());
                return p;
            });
            recorder.record(payment.risk());
            return new InitiateResult(PaymentView.of(payment), false);
        } catch (DuplicateIdempotencyKeyException raced) {
            // A concurrent request with this key committed first; our transaction rolled back. Answer as its replay.
            return tx.inTransaction(() -> replayOf(customer, key, hash)).orElseThrow(() -> raced);
        }
    }

    /** If the name could not be checked when the payee was set up, try again now, outside any transaction. */
    private NameMatch recheckIfUnavailable(Payee payee) {
        if (payee.nameCheck().match() != NameMatch.UNAVAILABLE) {
            return payee.nameCheck().match();
        }
        NameCheckResult retry = nameCheck.check(payee.name(), payee.sortCode(), payee.accountNumber());
        if (retry.match() != NameMatch.UNAVAILABLE) {
            tx.execute(() -> payees.updateNameCheck(payee.id(), retry));
        }
        return retry.match();
    }

    private Optional<InitiateResult> replayOf(CustomerId customer, String key, String hash) {
        return idempotency.find(customer, key).map(found -> {
            if (!found.requestHash().equals(hash)) {
                throw new IdempotencyConflictException();
            }
            Payment p = payments.find(found.paymentId())
                    .orElseThrow(() -> new IllegalStateException("idempotency record without its payment"));
            return new InitiateResult(PaymentView.of(p), true);
        });
    }

    public PaymentView confirm(CustomerId customer, PaymentId id, String acknowledgedWarning) {
        WarningCode acknowledged = WarningCode.parse(acknowledgedWarning);
        return change(customer, id, p -> p.confirm(customer, acknowledged, clock.instant()));
    }

    public PaymentView cancel(CustomerId customer, PaymentId id) {
        return change(customer, id, p -> p.cancel(customer, clock.instant()));
    }

    public PaymentView get(CustomerId customer, PaymentId id) {
        return tx.inTransaction(() -> PaymentView.of(owned(customer, id)));
    }

    public List<AuditEntryView> audit(CustomerId customer, PaymentId id) {
        return tx.inTransaction(() -> {
            owned(customer, id);
            return audit.forPayment(id).stream().map(AuditEntryView::of).toList();
        });
    }

    private PaymentView change(CustomerId customer, PaymentId id, Function<Payment, AuditEvent> action) {
        return tx.inTransaction(() -> {
            Payment p = owned(customer, id);
            AuditEvent event = action.apply(p);
            payments.update(p);
            audit.append(event);
            return PaymentView.of(p);
        });
    }

    private Payment owned(CustomerId customer, PaymentId id) {
        return payments.find(id).filter(p -> p.customer().equals(customer))
                .orElseThrow(() -> new NotFoundException("no such payment"));
    }

    private static String validKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new InvalidRequestException(
                    "an Idempotency-Key is 8 to 64 letters, digits, hyphens or underscores, for example a UUID");
        }
        return key;
    }

    private Money validAmount(long pence) {
        Money max = rules.policy().maxPayment();
        if (pence <= 0 || pence > max.pence()) {
            throw new InvalidRequestException("an amount is between 1p and " + max);
        }
        return Money.ofPence(pence);
    }

    static String requestHash(PayeeId payee, Money amount, PaymentReference reference) {
        String canonical = payee + "|" + amount.pence() + "|" + reference.value();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
