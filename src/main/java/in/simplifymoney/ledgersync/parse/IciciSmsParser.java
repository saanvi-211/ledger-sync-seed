package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ICICI Bank SMS.
 *
 * Two shapes are in production. The older one is the "Dear Customer, Acct
 * XX.... is debited with" sentence; the newer one is the terse
 * "ICICI Bank Acct XX.... Dr INR 90.00 on ...; MERCHANT ref no ... BalAvl Rs..."
 * line. Both are handled here.
 */
public final class IciciSmsParser implements MessageParser {

    public static final String SENDER = "VM-ICICIB-T";

    private static final Pattern V1 = Pattern.compile(
            "Acct XX(?<acct>\\d{4}) is (?<dir>debited|credited) with .*? "
                    + "on (?<when>\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2})\\. "
                    + "Info: (?<merchant>[^.]+)\\.");

    /** ICICI Bank Acct XX9075 Dr INR 90.00 on 29-Jul-2026 08:20; UPI/BARBER ref no 606679305058. */
    private static final Pattern V2 = Pattern.compile(
            "ICICI Bank Acct XX(?<acct>\\d{4}) (?<dir>Dr|Cr) "
                    + "(?:Rs\\.?|INR)\\s*[0-9,]+(?:\\.[0-9]{2})? "
                    + "on (?<when>\\d{2}-\\w{3}-\\d{4} \\d{2}:\\d{2}); "
                    + "(?<merchant>[^;]+?) ref no");

    @Override
    public boolean supports(RawMessage m) {
        return "sms".equals(m.channel()) && SENDER.equals(m.sender());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        Direction d;
        String acct;
        String when;
        String merchant;

        Matcher v1 = V1.matcher(m.body());
        if (v1.find()) {
            acct = v1.group("acct");
            when = v1.group("when");
            merchant = v1.group("merchant");
            d = "debited".equals(v1.group("dir"))
                    ? Direction.DEBIT : Direction.CREDIT;
        } else {
            Matcher v2 = V2.matcher(m.body());
            if (!v2.find()) return Optional.empty();
            acct = v2.group("acct");
            when = v2.group("when");
            merchant = v2.group("merchant");
            d = "Dr".equals(v2.group("dir"))
                    ? Direction.DEBIT : Direction.CREDIT;
        }

        BigDecimal amount = Amounts.first(m.body());
        OffsetDateTime at = Dates.ist(when);
        if (amount == null || at == null) return Optional.empty();

        return Optional.of(new ParsedTxn(acct, at, d, amount, merchant.trim(),
                Amounts.statedBalance(m.body()), m.messageId()));
    }
}
