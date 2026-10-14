package io.github.michaelsamuel2005.payguard.config;

import io.github.michaelsamuel2005.payguard.adapters.directory.SimulatedAccountNameDirectory;
import io.github.michaelsamuel2005.payguard.domain.Money;
import io.github.michaelsamuel2005.payguard.domain.ScamPolicy;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code payguard.*}. The scam policy defaults to {@link ScamPolicy#DEFAULT}, the evaluated one, so
 * the thresholds live in one place; set {@code payguard.policy.*} only to override it deliberately.
 */
@ConfigurationProperties(prefix = "payguard")
public record PayguardProperties(Policy policy, Directory directory, RateLimit rateLimit) {

    public PayguardProperties {
        directory = Objects.requireNonNullElse(directory, new Directory(null, 0, null, null));
        rateLimit = Objects.requireNonNullElse(rateLimit, new RateLimit(0));
    }

    public ScamPolicy scamPolicy() {
        return policy == null ? ScamPolicy.DEFAULT : policy.toScamPolicy();
    }

    public record Policy(int warnAt, int holdAt, long largeWithoutHistoryPence, int unusualMultiple,
                         long unusualFloorPence, int rapidNewPayees, Duration rapidWindow, Duration historyWindow,
                         long maxPaymentPence) {

        ScamPolicy toScamPolicy() {
            return new ScamPolicy(warnAt, holdAt, Money.ofPence(largeWithoutHistoryPence), unusualMultiple,
                    Money.ofPence(unusualFloorPence), rapidNewPayees, rapidWindow, historyWindow,
                    Money.ofPence(maxPaymentPence));
        }
    }

    /**
     * @param timeout             the longest a name check may take
     * @param breakerMinimumCalls calls in the window before the breaker may open (half of them failing opens it)
     * @param breakerWaitInOpen   how long the breaker stays open before trying again
     * @param failureMode         the simulated directory's failure mode at start-up
     */
    public record Directory(Duration timeout, int breakerMinimumCalls, Duration breakerWaitInOpen,
                            SimulatedAccountNameDirectory.FailureMode failureMode) {

        public Directory {
            timeout = Objects.requireNonNullElse(timeout, Duration.ofMillis(800));
            breakerMinimumCalls = breakerMinimumCalls > 0 ? breakerMinimumCalls : 5;
            breakerWaitInOpen = Objects.requireNonNullElse(breakerWaitInOpen, Duration.ofSeconds(30));
            failureMode = Objects.requireNonNullElse(failureMode, SimulatedAccountNameDirectory.FailureMode.NONE);
        }
    }

    public record RateLimit(int paymentsPerMinute) {

        public RateLimit {
            paymentsPerMinute = paymentsPerMinute > 0 ? paymentsPerMinute : 20;
        }
    }
}
