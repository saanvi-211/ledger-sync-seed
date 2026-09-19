package in.simplifymoney.ledgersync.parse;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rupee amounts as banks write them.
 *
 * Handles the prefixes we see in practice - "Rs.", "Rs ", "INR " - and strips
 * the thousands separators before handing back a BigDecimal.
 */
public final class Amounts {

    private Amounts() {}

    /**
     * Decimals are optional because banks write whole-rupee amounts without
     * them ("Rs.5", "INR 18,000"). Requiring exactly two decimals made
     * "first()" skip the real amount and grab the stated balance instead -
     * that is exactly what INC-2026-09-11 was.
     */
    private static final Pattern AMOUNT =
            Pattern.compile("(?:Rs\\.?|INR)\\s*([0-9,]+(?:\\.[0-9]{2})?)");

    private static final Pattern BALANCE = Pattern.compile(
            "(?:Avl\\s*Bal|Available\\s*Balance|BalAvl|Avl\\s*Limit)\\s*:?\\s*"
                    + "(?:Rs\\.?|INR)\\s*([0-9,]+\\.[0-9]{2})",
            Pattern.CASE_INSENSITIVE);

    /** The transaction amount: the first rupee figure in the message. */
    public static BigDecimal first(String body) {
        Matcher m = AMOUNT.matcher(body);
        if (!m.find()) return null;
        return toDecimal(m.group(1));
    }

    /** The balance the bank quoted, if it quoted one. */
    public static BigDecimal statedBalance(String body) {
        Matcher m = BALANCE.matcher(body);
        if (!m.find()) return null;
        return toDecimal(m.group(1));
    }

    private static BigDecimal toDecimal(String raw) {
        return new BigDecimal(raw.replace(",", "")).setScale(2);
    }
}
