package io.github.michaelsamuel2005.payguard.application;

import io.github.michaelsamuel2005.payguard.domain.AccountNumber;
import io.github.michaelsamuel2005.payguard.domain.CustomerId;
import io.github.michaelsamuel2005.payguard.domain.NameCheckResult;
import io.github.michaelsamuel2005.payguard.domain.NotFoundException;
import io.github.michaelsamuel2005.payguard.domain.Payee;
import io.github.michaelsamuel2005.payguard.domain.PayeeId;
import io.github.michaelsamuel2005.payguard.domain.SortCode;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public final class PayeeService {

    private final TransactionRunner tx;
    private final PayeeRepository payees;
    private final NameCheck nameCheck;
    private final Clock clock;
    private final Supplier<UUID> ids;

    public PayeeService(TransactionRunner tx, PayeeRepository payees, NameCheck nameCheck, Clock clock,
                        Supplier<UUID> ids) {
        this.tx = Objects.requireNonNull(tx, "tx");
        this.payees = Objects.requireNonNull(payees, "payees");
        this.nameCheck = Objects.requireNonNull(nameCheck, "nameCheck");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    /** Checks the name against the account, then saves the payee whatever the result: the result travels with it. */
    public PayeeView register(CustomerId customer, String name, String sortCode, String accountNumber) {
        String validName = Payee.validName(name);
        SortCode sc = new SortCode(sortCode);
        AccountNumber an = new AccountNumber(accountNumber);
        NameCheckResult check = nameCheck.check(validName, sc, an); // outside the transaction: a network call
        Payee payee = new Payee(new PayeeId(ids.get()), customer, validName, sc, an, check, clock.instant());
        tx.execute(() -> payees.insert(payee));
        return PayeeView.of(payee);
    }

    public PayeeView get(CustomerId customer, PayeeId id) {
        return tx.inTransaction(() -> payees.find(customer, id)).map(PayeeView::of)
                .orElseThrow(() -> new NotFoundException("no such payee"));
    }

    public List<PayeeView> list(CustomerId customer) {
        return tx.inTransaction(() -> payees.list(customer)).stream().map(PayeeView::of).toList();
    }
}
