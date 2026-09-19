package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * The identity of a real transaction: who, when, which way, how much.
 *
 * Two messages evidence the same transaction when they describe the same
 * money moving on the same account at the same minute. Source messages are
 * merged on this key, re-ingesting is idempotent, and Backfill uses it to
 * refuse duplicates even when the SQL store hands it the same row twice.
 */
public final class LedgerIdentity {

    private LedgerIdentity() {}

    private static final DateTimeFormatter MINUTE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    /** account|yyyy-MM-ddTHH:mm|DEBIT|1234.56 - all IST, minute precision. */
    public static String of(NormalizedTxn t) {
        String minute = t.occurredAt()
                .atZoneSameInstant(java.time.ZoneId.of("Asia/Kolkata"))
                .truncatedTo(ChronoUnit.MINUTES)
                .format(MINUTE);
        return t.accountLast4() + "|" + minute + "|" + t.direction().name()
                + "|" + t.amount().toPlainString();
    }
}