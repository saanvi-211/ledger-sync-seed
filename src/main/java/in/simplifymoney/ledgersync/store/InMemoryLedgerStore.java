package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Used by SelfCheck and by tests. Merges on transaction identity. */
public final class InMemoryLedgerStore implements LedgerStore {

    private final Map<String, NormalizedTxn> rows = new LinkedHashMap<>();
    private final List<BalancePoint> points = new ArrayList<>();

    @Override
    public void save(NormalizedTxn txn) {
        rows.put(LedgerIdentity.of(txn), txn);
    }

    @Override
    public void upsert(NormalizedTxn txn) {
        String key = LedgerIdentity.of(txn);
        NormalizedTxn existing = rows.get(key);
        if (existing == null) {
            rows.put(key, txn);
            return;
        }
        TreeSet<String> ids = new TreeSet<>(existing.sourceMessageIds());
        ids.addAll(txn.sourceMessageIds());
        rows.put(key, new NormalizedTxn(existing.accountLast4(),
                existing.occurredAt(), existing.direction(), existing.amount(),
                existing.category(), existing.merchant(), List.copyOf(ids)));
    }

    @Override
    public void saveBalancePoint(BalancePoint point) {
        for (BalancePoint p : points) {
            if (p.accountLast4().equals(point.accountLast4())
                    && p.asOf().equals(point.asOf())
                    && p.statedBalance().equals(point.statedBalance())) return;
        }
        points.add(point);
    }

    @Override
    public List<BalancePoint> balancePoints() {
        return Collections.unmodifiableList(points);
    }

    @Override
    public List<NormalizedTxn> all() {
        List<NormalizedTxn> out = new ArrayList<>(rows.values());
        out.sort(Comparator.comparing(NormalizedTxn::occurredAt)
                .thenComparing(NormalizedTxn::accountLast4)
                .thenComparing(NormalizedTxn::amount));
        return Collections.unmodifiableList(out);
    }

    @Override
    public long count() {
        return rows.size();
    }
}