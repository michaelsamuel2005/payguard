package io.github.michaelsamuel2005.payguard.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Compares the name a payer entered with the name on the account, in the spirit of Confirmation of Payee.
 *
 * <p>Both names are normalised first: case, accents, punctuation and titles are ignored, {@code &} reads as AND,
 * and LIMITED, COMPANY and INCORPORATED read as LTD, CO and INC. Then:
 * <ul>
 *   <li><b>MATCH</b>: the normalised names are identical.</li>
 *   <li><b>CLOSE_MATCH</b> for a person: every name lines up one to one, allowing an initial for a first name
 *       ({@code J SMITH}), one typo in a name of three or more letters ({@code JON}, {@code SMYTH}), a different
 *       order ({@code SMITH JOHN}), and an extra middle name or middle initial on either side. The account
 *       holder's first and last names must both be matched, and the last name by more than an initial.</li>
 *   <li><b>CLOSE_MATCH</b> for a business: the same name with a missing or different legal suffix
 *       ({@code ACME} for {@code ACME LTD}), or one typo per word in the same words.</li>
 *   <li><b>NO_MATCH</b>: anything else, including an empty name.</li>
 * </ul>
 * Joint accounts and trading names are out of scope.
 */
public final class NameMatcher {

    private static final Set<String> TITLES =
            Set.of("MR", "MRS", "MS", "MISS", "MX", "DR", "PROF", "SIR", "DAME", "REV");
    private static final Map<String, String> CANONICAL =
            Map.of("LIMITED", "LTD", "COMPANY", "CO", "INCORPORATED", "INC");
    private static final Set<String> BUSINESS_SUFFIXES = Set.of("LTD", "PLC", "LLP", "LP", "INC", "CO");

    private enum Kind { EXACT, INITIAL, TYPO }

    public NameMatch compare(String entered, String accountHolder) {
        List<String> a = tokens(entered);
        List<String> b = tokens(accountHolder);
        if (a.isEmpty() || b.isEmpty()) {
            return NameMatch.NO_MATCH;
        }
        if (a.equals(b)) {
            return NameMatch.MATCH;
        }
        if (isBusiness(a) || isBusiness(b)) {
            return compareBusiness(a, b);
        }
        return closePersonalMatch(a, b) ? NameMatch.CLOSE_MATCH : NameMatch.NO_MATCH;
    }

    static List<String> tokens(String name) {
        if (name == null) {
            return List.of();
        }
        String s = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        s = s.toUpperCase(Locale.ROOT)
                .replace("&", " AND ")
                .replace("'", "")
                .replace("’", "")
                .replaceAll("[^A-Z0-9]+", " ")
                .trim();
        List<String> out = new ArrayList<>();
        for (String t : s.split(" ")) {
            if (!t.isEmpty() && !TITLES.contains(t)) {
                out.add(CANONICAL.getOrDefault(t, t));
            }
        }
        return out;
    }

    private static boolean isBusiness(List<String> tokens) {
        return tokens.stream().anyMatch(BUSINESS_SUFFIXES::contains);
    }

    private static NameMatch compareBusiness(List<String> a, List<String> b) {
        List<String> coreA = withoutSuffixes(a);
        List<String> coreB = withoutSuffixes(b);
        if (coreA.isEmpty() || coreB.isEmpty() || coreA.size() != coreB.size()) {
            return NameMatch.NO_MATCH;
        }
        for (int i = 0; i < coreA.size(); i++) {
            String x = coreA.get(i);
            String y = coreB.get(i);
            if (!x.equals(y) && !isTypo(x, y)) {
                return NameMatch.NO_MATCH;
            }
        }
        return NameMatch.CLOSE_MATCH;
    }

    private static List<String> withoutSuffixes(List<String> tokens) {
        return tokens.stream().filter(t -> !BUSINESS_SUFFIXES.contains(t)).toList();
    }

    private static boolean closePersonalMatch(List<String> entered, List<String> holder) {
        int n = entered.size();
        int m = holder.size();
        int[] enteredForHolder = new int[m];
        Kind[] kindForHolder = new Kind[m];
        boolean[] enteredUsed = new boolean[n];
        Arrays.fill(enteredForHolder, -1);
        // Best matches first, so an exact name is never spent on a weaker pairing.
        for (Kind kind : Kind.values()) {
            for (int j = 0; j < m; j++) {
                if (enteredForHolder[j] != -1) {
                    continue;
                }
                for (int i = 0; i < n; i++) {
                    if (!enteredUsed[i] && matches(kind, entered.get(i), holder.get(j))) {
                        enteredForHolder[j] = i;
                        kindForHolder[j] = kind;
                        enteredUsed[i] = true;
                        break;
                    }
                }
            }
        }
        if (enteredForHolder[0] == -1 || enteredForHolder[m - 1] == -1 || kindForHolder[m - 1] == Kind.INITIAL) {
            return false; // first and last names must match, and the last name by more than an initial
        }
        for (int i = 0; i < n; i++) {
            boolean middle = i > 0 && i < n - 1;
            if (!enteredUsed[i] && !middle) {
                return false; // only a middle name or initial may be left over on the payer's side
            }
        }
        return true; // unmatched holder names here can only be middle names
    }

    private static boolean matches(Kind kind, String e, String h) {
        return switch (kind) {
            case EXACT -> e.equals(h);
            case INITIAL -> (e.length() == 1 && h.startsWith(e)) || (h.length() == 1 && e.startsWith(h));
            case TYPO -> isTypo(e, h);
        };
    }

    private static boolean isTypo(String a, String b) {
        return Math.min(a.length(), b.length()) >= 3 && editDistance(a, b) <= 1;
    }

    /** Optimal string alignment distance: insertions, deletions, substitutions and adjacent swaps cost one. */
    static int editDistance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[a.length()][b.length()];
    }
}
