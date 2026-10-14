package io.github.michaelsamuel2005.payguard.application;

import static io.github.michaelsamuel2005.payguard.application.ServiceFixture.ALICE;
import static io.github.michaelsamuel2005.payguard.application.ServiceFixture.BOB;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.michaelsamuel2005.payguard.domain.ConflictException;
import io.github.michaelsamuel2005.payguard.domain.InvalidRequestException;
import io.github.michaelsamuel2005.payguard.domain.NotFoundException;
import io.github.michaelsamuel2005.payguard.domain.PayeeId;
import org.junit.jupiter.api.Test;

class PayeeServiceTest {

    private final ServiceFixture f = new ServiceFixture();

    @Test
    void registering_a_payee_checks_the_name_and_masks_the_account() {
        PayeeView v = f.payeeService.register(ALICE, "John Smith", "12-34-56", "12345678");
        assertThat(v.nameCheck()).isEqualTo("MATCH");
        assertThat(v.accountName()).isNull();
        assertThat(v.accountNumber()).isEqualTo("****5678");
        assertThat(v.sortCode()).isEqualTo("12-34-56");
    }

    @Test
    void a_close_match_shows_the_real_name_and_a_no_match_shows_nothing() {
        PayeeView close = f.payeeService.register(ALICE, "Jon Smith", "123456", "12345678");
        assertThat(close.nameCheck()).isEqualTo("CLOSE_MATCH");
        assertThat(close.accountName()).isEqualTo("JOHN SMITH");
        PayeeView none = f.payeeService.register(ALICE, "Mike Jones", "20-30-40", "33334444");
        assertThat(none.nameCheck()).isEqualTo("NO_MATCH");
        assertThat(none.accountName()).isNull();
    }

    @Test
    void an_unknown_account_is_a_no_match() {
        assertThat(f.payeeService.register(ALICE, "Ann Lee", "99-99-99", "00000000").nameCheck()).isEqualTo("NO_MATCH");
    }

    @Test
    void when_the_directory_is_down_the_payee_is_saved_as_unchecked() {
        f.directory.setDown(true);
        assertThat(f.payeeService.register(ALICE, "John Smith", "12-34-56", "12345678").nameCheck())
                .isEqualTo("UNAVAILABLE");
    }

    @Test
    void the_same_account_twice_is_a_conflict_for_one_customer_but_not_across_customers() {
        f.payeeService.register(ALICE, "John Smith", "12-34-56", "12345678");
        assertThatThrownBy(() -> f.payeeService.register(ALICE, "John Smith", "123456", "12345678"))
                .isInstanceOf(ConflictException.class);
        f.payeeService.register(BOB, "John Smith", "12-34-56", "12345678");
        assertThat(f.payeeService.list(ALICE)).hasSize(1);
        assertThat(f.payeeService.list(BOB)).hasSize(1);
    }

    @Test
    void a_customer_reads_only_their_own_payee() {
        PayeeView v = f.payeeService.register(ALICE, "John Smith", "12-34-56", "12345678");
        assertThat(f.payeeService.get(ALICE, PayeeId.parse(v.id())))
                .isEqualTo(v);
        assertThatThrownBy(() -> f.payeeService.get(BOB, PayeeId.parse(v.id())))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void bad_details_are_refused_before_the_directory_is_asked() {
        assertThatThrownBy(() -> f.payeeService.register(ALICE, "John Smith", "12-34", "12345678"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> f.payeeService.register(ALICE, " ", "12-34-56", "12345678"))
                .isInstanceOf(InvalidRequestException.class);
        assertThat(f.directory.calls()).isZero();
    }
}
