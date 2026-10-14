package io.github.michaelsamuel2005.payguard.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NameMatcherTest {

    private final NameMatcher matcher = new NameMatcher();

    @ParameterizedTest(name = "{0} vs {1} -> {2}")
    @CsvSource(delimiter = '|', textBlock = """
            John Smith          | JOHN SMITH           | MATCH
            mr  john smith.     | JOHN SMITH           | MATCH
            Zoë Brown           | ZOE BROWN            | MATCH
            Mary-Jane Watson    | MARY JANE WATSON     | MATCH
            Smith & Sons Ltd    | SMITH AND SONS LIMITED | MATCH
            J Smith             | JOHN SMITH           | CLOSE_MATCH
            John Smith          | J SMITH              | CLOSE_MATCH
            Jon Smith           | JOHN SMITH           | CLOSE_MATCH
            Jhon Smith          | JOHN SMITH           | CLOSE_MATCH
            John Smyth          | JOHN SMITH           | CLOSE_MATCH
            Smith John          | JOHN SMITH           | CLOSE_MATCH
            John A Smith        | JOHN SMITH           | CLOSE_MATCH
            John Smith          | JOHN ALAN SMITH      | CLOSE_MATCH
            Sean O'Brien        | SEAN OBRIEN          | MATCH
            OBrien Sean         | SEAN O'BRIEN         | CLOSE_MATCH
            Acme                | ACME TRADING LIMITED | NO_MATCH
            Acme Trading        | ACME TRADING LIMITED | CLOSE_MATCH
            Acme Tradng Ltd     | ACME TRADING LIMITED | CLOSE_MATCH
            Acme Trading Group  | ACME TRADING LIMITED | NO_MATCH
            Jane Smith          | JOHN SMITH           | NO_MATCH
            John                | JOHN SMITH           | NO_MATCH
            Smith               | JOHN SMITH           | NO_MATCH
            John S              | JOHN SMITH           | NO_MATCH
            John Alan Smith     | JOHN SMITH           | CLOSE_MATCH
            Alan Smith John     | JOHN SMITH           | NO_MATCH
            Alex Morgan         | DANIEL HUGHES        | NO_MATCH
            Dr                  | JOHN SMITH           | NO_MATCH
            """)
    void compares_names_like_confirmation_of_payee(String entered, String holder, NameMatch expected) {
        assertThat(matcher.compare(entered, holder)).isEqualTo(expected);
    }

    @Test
    void an_empty_or_missing_name_never_matches() {
        assertThat(matcher.compare("", "JOHN SMITH")).isEqualTo(NameMatch.NO_MATCH);
        assertThat(matcher.compare(null, "JOHN SMITH")).isEqualTo(NameMatch.NO_MATCH);
        assertThat(matcher.compare("John Smith", "")).isEqualTo(NameMatch.NO_MATCH);
    }

    @Test
    void edit_distance_counts_an_adjacent_swap_as_one() {
        assertThat(NameMatcher.editDistance("JHON", "JOHN")).isEqualTo(1);
        assertThat(NameMatcher.editDistance("JANE", "JOHN")).isEqualTo(3);
        assertThat(NameMatcher.editDistance("", "ABC")).isEqualTo(3);
    }
}
