package io.github.michaelsamuel2005.payguard.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ValueObjectsTest {

    @Test
    void money_is_held_in_pence_and_cannot_be_negative() {
        assertThat(Money.ofPounds(12).pence()).isEqualTo(1200);
        assertThat(Money.ofPence(123456)).hasToString("£1234.56");
        assertThat(Money.ofPence(5)).hasToString("£0.05");
        assertThatThrownBy(() -> Money.ofPence(-1)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> Money.ofPence(Long.MAX_VALUE).times(2)).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void sort_codes_accept_the_usual_formats_and_nothing_else() {
        assertThat(new SortCode("12-34-56").value()).isEqualTo("123456");
        assertThat(new SortCode("12 34 56").formatted()).isEqualTo("12-34-56");
        for (String bad : new String[] {"12345", "1234567", "12-34-5a", "", null}) {
            assertThatThrownBy(() -> new SortCode(bad)).isInstanceOf(InvalidRequestException.class);
        }
    }

    @Test
    void an_account_number_never_prints_in_full() {
        AccountNumber account = new AccountNumber("12345678");
        assertThat(account.masked()).isEqualTo("****5678");
        assertThat(account).hasToString("****5678");
        assertThat("paid " + account).doesNotContain("1234");
        assertThatThrownBy(() -> new AccountNumber("1234567")).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void a_reference_is_short_and_plain() {
        assertThat(new PaymentReference("  RENT OCT  ").value()).isEqualTo("RENT OCT");
        assertThatThrownBy(() -> new PaymentReference("THIS IS NINETEEN CH"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> new PaymentReference("<script>")).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> new PaymentReference("   ")).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void ids_reject_anything_that_is_not_a_uuid() {
        assertThatThrownBy(() -> PaymentId.parse("42")).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> PayeeId.parse(null)).isInstanceOf(InvalidRequestException.class);
        assertThat(PaymentId.parse("6f1c2c5e-6a0e-4d7f-9a43-2a1f4a0c9b11").value()).isNotNull();
    }

    @Test
    void a_payee_name_needs_a_letter_and_at_most_70_characters() {
        assertThat(Payee.validName("  Jo Smith ")).isEqualTo("Jo Smith");
        assertThatThrownBy(() -> Payee.validName("12345")).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> Payee.validName("a".repeat(71))).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void only_a_close_match_carries_the_account_holders_name() {
        assertThat(NameCheckResult.closeMatch("JOHN SMITH").accountName()).isEqualTo("JOHN SMITH");
        assertThat(NameCheckResult.of(NameMatch.NO_MATCH).accountName()).isNull();
        assertThatThrownBy(() -> new NameCheckResult(NameMatch.NO_MATCH, "JOHN SMITH"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NameCheckResult(NameMatch.CLOSE_MATCH, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
