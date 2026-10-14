package io.github.michaelsamuel2005.payguard.application;

import io.github.michaelsamuel2005.payguard.domain.CustomerId;
import io.github.michaelsamuel2005.payguard.domain.NameMatcher;
import io.github.michaelsamuel2005.payguard.domain.PayeeId;
import io.github.michaelsamuel2005.payguard.domain.RiskAssessment;
import io.github.michaelsamuel2005.payguard.domain.ScamPolicy;
import io.github.michaelsamuel2005.payguard.domain.ScamRules;
import io.github.michaelsamuel2005.payguard.testsupport.FakeDirectory;
import io.github.michaelsamuel2005.payguard.testsupport.FakeTransactions;
import io.github.michaelsamuel2005.payguard.testsupport.InMemoryStores;
import io.github.michaelsamuel2005.payguard.testsupport.MutableClock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The services wired to in-memory stores, a fake directory and a hand-moved clock. */
final class ServiceFixture {

    static final CustomerId ALICE = new CustomerId("alice");
    static final CustomerId BOB = new CustomerId("bob");

    final MutableClock clock = new MutableClock(Instant.parse("2026-10-14T09:00:00Z"));
    final InMemoryStores.Payees payees = new InMemoryStores.Payees();
    final InMemoryStores.Payments payments = new InMemoryStores.Payments();
    final InMemoryStores.Keys keys = new InMemoryStores.Keys();
    final InMemoryStores.Audit audit = new InMemoryStores.Audit();
    final FakeTransactions tx = new FakeTransactions().with(payees, payments, keys, audit);
    final FakeDirectory directory = new FakeDirectory(tx::isActive)
            .account("12-34-56", "12345678", "JOHN SMITH")
            .account("20-30-40", "33334444", "PRIYA PATEL");
    final List<RiskAssessment> recorded = new ArrayList<>();
    final NameCheck nameCheck = new NameCheck(directory, new NameMatcher());
    final PayeeService payeeService = new PayeeService(tx, payees, nameCheck, clock, UUID::randomUUID);
    final PaymentService paymentService = new PaymentService(tx, payees, payments, keys, audit, nameCheck,
            new ScamRules(ScamPolicy.DEFAULT.withThresholds(2, 4)), recorded::add, clock, UUID::randomUUID);
    final ReviewService reviewService = new ReviewService(tx, payments, audit, clock);

    PayeeId payee(CustomerId customer, String name, String sortCode, String account) {
        return PayeeId.parse(payeeService.register(customer, name, sortCode, account).id());
    }

    static String key() {
        return UUID.randomUUID().toString();
    }
}
