package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bank transaction alert emails.
 *
 * Both banks send the same shape: a "Date:" header carrying the transaction
 * time (always with an offset - +05:30 normally, but at least one HDFC email
 * uses +0000 so the offset must actually be honoured), then a sentence "Your
 * account ending XXXX has been (debited|credited) with ...", then a
 * "Merchant / Remarks:" line.
 */
public final class EmailParser implements MessageParser {

    private static final java.util.Set<String> SENDERS =
            java.util.Set.of("alerts@hdfcbank.net", "alerts@icicibank.com");

    /** Sat, 04 Jul 2026 12:24:00 +0530 */
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern(
            "EEE, d MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    private static final Pattern HEADER = Pattern.compile("^Date:\\s*(.+)$",
            Pattern.MULTILINE);
    private static final Pattern TXN = Pattern.compile(
            "Your account ending (\\d{4}) has been (debited|credited) with "
                    + "(?:Rs\\.?|INR)\\s*([0-9,]+(?:\\.[0-9]{2})?)\\.");
    private static final Pattern MERCHANT = Pattern.compile(
            "Merchant / Remarks:\\s*(.+)$", Pattern.MULTILINE);

    @Override
    public boolean supports(RawMessage m) {
        return "email".equals(m.channel()) && SENDERS.contains(m.sender());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        Matcher when = HEADER.matcher(m.body());
        Matcher txn = TXN.matcher(m.body());
        if (!when.find() || !txn.find()) return Optional.empty();

        OffsetDateTime at = parseDate(when.group(1).trim());
        if (at == null) return Optional.empty();

        Direction d = "debited".equals(txn.group(2))
                ? Direction.DEBIT : Direction.CREDIT;
        BigDecimal amount = toDecimal(txn.group(3));

        Matcher merchant = MERCHANT.matcher(m.body());
        String name = merchant.find() ? merchant.group(1).trim() : "";

        return Optional.of(new ParsedTxn(txn.group(1), at, d, amount, name,
                null, m.messageId()));
    }

    private OffsetDateTime parseDate(String header) {
        try {
            return OffsetDateTime.parse(header, DATE)
                    .withOffsetSameInstant(Dates.IST);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static BigDecimal toDecimal(String raw) {
        return new BigDecimal(raw.replace(",", "")).setScale(2);
    }
}
