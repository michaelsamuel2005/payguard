package io.github.michaelsamuel2005.payguard.application;

import io.github.michaelsamuel2005.payguard.domain.AccountNumber;
import io.github.michaelsamuel2005.payguard.domain.SortCode;
import java.util.Optional;

/** Who holds an account: the question a Confirmation of Payee request asks the receiving bank. */
public interface AccountNameDirectory {

    /**
     * @return the account holder's name, or empty if there is no such account
     * @throws DirectoryUnavailableException (or any other runtime exception) if the question could not be answered
     */
    Optional<String> accountName(SortCode sortCode, AccountNumber accountNumber);
}
