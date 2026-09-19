package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.util.List;

/**
 * Where transactions live.
 *
 * Note what this interface does NOT promise about {@link #save}: that saving
 * the same transaction twice results in one row. Idempotence lives in
 * {@link #upsert}, which merges on {@link LedgerIdentity} - re-reading the
 * same SMS, or re-running an ingest, never produces a second row.
 */
public interface LedgerStore {

    void save(NormalizedTxn txn);

    /** Save, or merge into the existing transaction with the same identity. */
    default void upsert(NormalizedTxn txn) {
        save(txn);
    }

    /** Remember a balance the bank stated in a message. */
    default void saveBalancePoint(BalancePoint point) {
    }

    default List<BalancePoint> balancePoints() {
        return List.of();
    }

    List<NormalizedTxn> all();

    long count();
}
