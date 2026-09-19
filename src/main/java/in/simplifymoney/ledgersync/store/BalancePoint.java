package in.simplifymoney.ledgersync.store;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * A balance the bank stated alongside a transaction in a message ("Avl Bal",
 * "BalAvl", "Available Balance"), keyed to the moment the bank says it was the
 * balance. Reconciliation walks these to find balance movements that the
 * ledger cannot account for.
 */
public record BalancePoint(
        String accountLast4,
        OffsetDateTime asOf,
        BigDecimal statedBalance) {
}