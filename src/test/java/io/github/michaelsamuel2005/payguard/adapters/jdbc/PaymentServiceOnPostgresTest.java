package io.github.michaelsamuel2005.payguard.adapters.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.michaelsamuel2005.payguard.adapters.directory.SimulatedAccountNameDirectory;
import io.github.michaelsamuel2005.payguard.application.InitiateResult;
import io.github.michaelsamuel2005.payguard.application.NameCheck;
import io.github.michaelsamuel2005.payguard.application.PayeeService;
import io.github.michaelsamuel2005.payguard.application.PaymentService;
import io.github.michaelsamuel2005.payguard.application.ReviewService;
import io.github.michaelsamuel2005.payguard.domain.CustomerId;
import io.github.michaelsamuel2005.payguard.domain.NameMatcher;
import io.github.michaelsamuel2005.payguard.domain.PayeeId;
import io.github.michaelsamuel2005.payguard.domain.PaymentId;
import io.github.michaelsamuel2005.payguard.domain.ScamPolicy;
import io.github.michaelsamuel2005.payguard.domain.ScamRules;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The services on the real database, including two clients racing with the same Idempotency-Key. */
class PaymentServiceOnPostgresTest {

    private static DataSource ds;
    private static PayeeService payees;
    private static PaymentService payments;
    private static ReviewService reviews;
    private static SimulatedAccountNameDirectory directory;

    @BeforeAll
    static void wire() {
        ds = TestDatabase.freshSchema();
        JdbcTransactionRunner tx = new JdbcTransactionRunner(ds);
        JdbcPayeeRepository payeeRepo = new JdbcPayeeRepository(tx);
        JdbcPaymentRepository paymentRepo = new JdbcPaymentRepository(tx);
        JdbcAuditLog audit = new JdbcAuditLog(tx);
        directory = SimulatedAccountNameDirectory.withSampleAccounts();
        NameCheck check = new NameCheck(directory, new NameMatcher());
        Clock clock = Clock.systemUTC();
        payees = new PayeeService(tx, payeeRepo, check, clock, UUID::randomUUID);
        payments = new PaymentService(tx, payeeRepo, paymentRepo, new JdbcIdempotencyStore(tx), audit, check,
                new ScamRules(ScamPolicy.DEFAULT), a -> { }, clock, UUID::randomUUID);
        reviews = new ReviewService(tx, paymentRepo, audit, clock);
    }

    private static CustomerId someone() {
        return new CustomerId("c-" + UUID.randomUUID());
    }

    @Test
    void eight_clients_retrying_with_one_key_create_exactly_one_payment() throws Exception {
        CustomerId customer = someone();
        PayeeId john = PayeeId.parse(payees.register(customer, "John Smith", "12-34-56", "12345678").id());
        String key = UUID.randomUUID().toString();
        int clients = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        try {
            List<Future<InitiateResult>> futures = new ArrayList<>();
            for (int i = 0; i < clients; i++) {
                Callable<InitiateResult> call = () -> {
                    start.await();
                    return payments.initiate(customer, key, john, 1_500, "LUNCH");
                };
                futures.add(pool.submit(call));
            }
            start.countDown();
            List<InitiateResult> results = new ArrayList<>();
            for (Future<InitiateResult> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            assertThat(results).extracting(r -> r.payment().id()).containsOnly(results.get(0).payment().id());
            assertThat(results).filteredOn(r -> !r.replayed()).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("select count(*) from payments where customer_id = ?", customer.value())).isEqualTo(1);
        assertThat(count("select count(*) from audit_events a join payments p on p.id = a.payment_id "
                + "where p.customer_id = ?", customer.value())).isEqualTo(1);
    }

    @Test
    void a_held_payment_is_decided_once_and_the_trail_says_who_and_why() {
        CustomerId customer = someone();
        PayeeId wrong = PayeeId.parse(payees.register(customer, "Mike Jones", "20-30-40", "33334444").id());
        InitiateResult r = payments.initiate(customer, UUID.randomUUID().toString(), wrong, 400_000, "INVOICE");
        assertThat(r.payment().status()).isEqualTo("HELD");
        PaymentId id = PaymentId.parse(r.payment().id());
        reviews.decide("rita", id, "REJECT", "account reported as a mule");
        assertThat(payments.audit(customer, id)).extracting(e -> e.action() + ":" + e.to() + ":" + e.actor())
                .containsExactly("INITIATED:HELD:" + customer, "REJECTED:REJECTED:rita");
    }

    @Test
    void when_the_directory_is_down_nothing_is_silently_released() {
        CustomerId customer = someone();
        directory.setFailureMode(SimulatedAccountNameDirectory.FailureMode.DOWN);
        try {
            PayeeId john = PayeeId.parse(payees.register(customer, "John Smith", "12-34-56", "12345678").id());
            InitiateResult r = payments.initiate(customer, UUID.randomUUID().toString(), john, 1_000, "LUNCH");
            assertThat(r.payment().status()).isEqualTo("AWAITING_CONFIRMATION");
            assertThat(r.payment().warning().code()).isEqualTo("NAME_CHECK_UNAVAILABLE");
        } finally {
            directory.setFailureMode(SimulatedAccountNameDirectory.FailureMode.NONE);
        }
    }

    private static int count(String sql, String customer) throws Exception {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, customer);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
