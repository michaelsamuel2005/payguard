package io.github.michaelsamuel2005.payguard.config;

import io.github.michaelsamuel2005.payguard.adapters.directory.SimulatedAccountNameDirectory;
import io.github.michaelsamuel2005.payguard.adapters.jdbc.JdbcAuditLog;
import io.github.michaelsamuel2005.payguard.adapters.jdbc.JdbcIdempotencyStore;
import io.github.michaelsamuel2005.payguard.adapters.jdbc.JdbcPayeeRepository;
import io.github.michaelsamuel2005.payguard.adapters.jdbc.JdbcPaymentRepository;
import io.github.michaelsamuel2005.payguard.adapters.jdbc.JdbcTransactionRunner;
import io.github.michaelsamuel2005.payguard.adapters.observability.MicrometerDecisionRecorder;
import io.github.michaelsamuel2005.payguard.adapters.resilience.ResilientAccountNameDirectory;
import io.github.michaelsamuel2005.payguard.application.AccountNameDirectory;
import io.github.michaelsamuel2005.payguard.application.NameCheck;
import io.github.michaelsamuel2005.payguard.application.PayeeService;
import io.github.michaelsamuel2005.payguard.application.PaymentRateLimiter;
import io.github.michaelsamuel2005.payguard.application.PaymentService;
import io.github.michaelsamuel2005.payguard.application.ReviewService;
import io.github.michaelsamuel2005.payguard.domain.NameMatcher;
import io.github.michaelsamuel2005.payguard.domain.ScamRules;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** Builds the plain-Java core and its adapters. Nothing in the core is annotated; this is the only wiring. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PayguardProperties.class)
public class WiringConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    JdbcTransactionRunner transactionRunner(DataSource dataSource) {
        return new JdbcTransactionRunner(dataSource);
    }

    @Bean
    JdbcPayeeRepository payeeRepository(JdbcTransactionRunner tx) {
        return new JdbcPayeeRepository(tx);
    }

    @Bean
    JdbcPaymentRepository paymentRepository(JdbcTransactionRunner tx) {
        return new JdbcPaymentRepository(tx);
    }

    @Bean
    JdbcIdempotencyStore idempotencyStore(JdbcTransactionRunner tx) {
        return new JdbcIdempotencyStore(tx);
    }

    @Bean
    JdbcAuditLog auditLog(JdbcTransactionRunner tx) {
        return new JdbcAuditLog(tx);
    }

    @Bean
    SimulatedAccountNameDirectory simulatedDirectory(PayguardProperties properties) {
        SimulatedAccountNameDirectory directory = SimulatedAccountNameDirectory.withSampleAccounts();
        directory.setFailureMode(properties.directory().failureMode());
        return directory;
    }

    @Bean(destroyMethod = "close")
    @Primary
    ResilientAccountNameDirectory accountNameDirectory(SimulatedAccountNameDirectory simulated,
                                                       PayguardProperties properties, MeterRegistry meters) {
        PayguardProperties.Directory d = properties.directory();
        CircuitBreaker breaker = CircuitBreaker.of("account-name-directory", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(Math.max(10, d.breakerMinimumCalls()))
                .minimumNumberOfCalls(d.breakerMinimumCalls())
                .failureRateThreshold(50)
                .waitDurationInOpenState(d.breakerWaitInOpen())
                .permittedNumberOfCallsInHalfOpenState(2)
                .build());
        ResilientAccountNameDirectory directory = new ResilientAccountNameDirectory(simulated, breaker, d.timeout());
        Gauge.builder("payguard.directory.circuit.open", directory,
                        x -> x.state() == CircuitBreaker.State.OPEN ? 1.0 : 0.0)
                .description("1 while the name-check circuit breaker is open").register(meters);
        return directory;
    }

    @Bean
    NameCheck nameCheck(AccountNameDirectory directory) {
        return new NameCheck(directory, new NameMatcher());
    }

    @Bean
    ScamRules scamRules(PayguardProperties properties) {
        return new ScamRules(properties.scamPolicy());
    }

    @Bean
    PayeeService payeeService(JdbcTransactionRunner tx, JdbcPayeeRepository payees, NameCheck nameCheck,
                              Clock clock) {
        return new PayeeService(tx, payees, nameCheck, clock, UUID::randomUUID);
    }

    @Bean
    PaymentService paymentService(JdbcTransactionRunner tx, JdbcPayeeRepository payees,
                                  JdbcPaymentRepository payments, JdbcIdempotencyStore keys, JdbcAuditLog audit,
                                  NameCheck nameCheck, ScamRules rules, MeterRegistry meters, Clock clock) {
        return new PaymentService(tx, payees, payments, keys, audit, nameCheck, rules,
                new MicrometerDecisionRecorder(meters), clock, UUID::randomUUID);
    }

    @Bean
    ReviewService reviewService(JdbcTransactionRunner tx, JdbcPaymentRepository payments, JdbcAuditLog audit,
                                Clock clock, MeterRegistry meters) {
        ReviewService reviews = new ReviewService(tx, payments, audit, clock);
        Gauge.builder("payguard.reviews.held", reviews, ReviewService::heldCount)
                .description("payments waiting for a reviewer").register(meters);
        return reviews;
    }

    @Bean
    PaymentRateLimiter paymentRateLimiter(PayguardProperties properties, Clock clock) {
        return new PaymentRateLimiter(properties.rateLimit().paymentsPerMinute(), clock);
    }
}
