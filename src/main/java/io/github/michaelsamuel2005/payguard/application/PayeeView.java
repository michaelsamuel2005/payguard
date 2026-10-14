package io.github.michaelsamuel2005.payguard.application;

import io.github.michaelsamuel2005.payguard.domain.Payee;
import java.time.Instant;

/**
 * A payee as the API shows it. The account number is masked; {@code accountName} is set only for a close match.
 */
public record PayeeView(String id, String name, String sortCode, String accountNumber, String nameCheck,
                        String accountName, Instant createdAt) {

    public static PayeeView of(Payee p) {
        return new PayeeView(p.id().toString(), p.name(), p.sortCode().formatted(), p.accountNumber().masked(),
                p.nameCheck().match().name(), p.nameCheck().accountName(), p.createdAt());
    }
}
