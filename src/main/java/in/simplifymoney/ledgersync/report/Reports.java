package in.simplifymoney.ledgersync.report;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.store.BalancePoint;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The three reports the assignment asks for.
 *
 * summary() rolls MICRO spends up into a single line and keeps TRANSFER legs
 * out of spend and income - moving money between your own accounts is neither.
 *
 * reconciliation() walks the balances the banks stated alongside each message
 * and compares the movement between successive statements against what the
 * ledger can account for. Where the bank says money moved but no message
 * explains it, that is a discrepancy.
 */
public final class Reports {

    private Reports() {}

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    public static Map<String, Object> summary(List<NormalizedTxn> ledger) {
        Map<String, Object> accounts = new LinkedHashMap<>();
        for (String acct : new TreeSet<>(ledger.stream()
                .map(NormalizedTxn::accountLast4).toList())) {

            BigDecimal spend = ZERO;
            BigDecimal income = ZERO;
            BigDecimal microTotal = ZERO;
            BigDecimal transferredOut = ZERO;
            BigDecimal transferredIn = ZERO;
            int microCount = 0;

            for (NormalizedTxn t : ledger) {
                if (!t.accountLast4().equals(acct)) continue;
                switch (t.category()) {
                    case SPEND -> spend = spend.add(t.amount());
                    case INCOME -> income = income.add(t.amount());
                    case MICRO -> {
                        microCount++;
                        microTotal = microTotal.add(t.amount());
                    }
                    case TRANSFER -> {
                        if (t.direction() == Direction.DEBIT) {
                            transferredOut = transferredOut.add(t.amount());
                        } else {
                            transferredIn = transferredIn.add(t.amount());
                        }
                    }
                }
            }

            Map<String, Object> a = new LinkedHashMap<>();
            a.put("spend", spend.toPlainString());
            a.put("income", income.toPlainString());
            a.put("micro_count", microCount);
            a.put("micro_total", microTotal.toPlainString());
            a.put("transferred_out", transferredOut.toPlainString());
            a.put("transferred_in", transferredIn.toPlainString());
            accounts.put(acct, a);
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("accounts", accounts);
        return doc;
    }

    public static Map<String, Object> ledgerDocument(List<NormalizedTxn> ledger) {
        List<NormalizedTxn> ordered = ledger.stream()
                .sorted(Comparator.comparing(NormalizedTxn::occurredAt)
                        .thenComparing(NormalizedTxn::accountLast4)
                        .thenComparing(NormalizedTxn::amount))
                .toList();
        List<Object> rows = ordered.stream().map(t -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("account_last4", t.accountLast4());
            r.put("occurred_at", t.occurredAt().toString());
            r.put("direction", t.direction().name().toLowerCase());
            r.put("amount", t.amount().toPlainString());
            r.put("category", t.category().name());
            r.put("merchant", t.merchant());
            r.put("source_message_ids", t.sourceMessageIds());
            return (Object) r;
        }).toList();
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("transactions", rows);
        return doc;
    }

    /**
     * meta says what the bank is known to say this account opened and closed
     * at. points are the balances the messages quoted. The ledger's own math
     * is compared to both.
     */
    public static Map<String, Object> reconciliation(List<NormalizedTxn> ledger,
                                                    List<BalancePoint> points,
                                                    Map<String, AccountMeta> meta) {
        Map<String, Object> accounts = new LinkedHashMap<>();
        List<Object> discrepancies = new ArrayList<>();

        for (Map.Entry<String, AccountMeta> e : meta.entrySet()) {
            String acct = e.getKey();
            BigDecimal opening = new BigDecimal(e.getValue().openingBalance());
            BigDecimal bankClosing = new BigDecimal(e.getValue().closingBalance());

            List<NormalizedTxn> txns = ledger.stream()
                    .filter(t -> t.accountLast4().equals(acct))
                    .sorted(Comparator.comparing(NormalizedTxn::occurredAt))
                    .toList();

            BigDecimal derived = opening;
            for (NormalizedTxn t : txns) {
                derived = t.direction() == Direction.DEBIT
                        ? derived.subtract(t.amount()) : derived.add(t.amount());
            }

            Map<String, Object> a = new LinkedHashMap<>();
            a.put("opening_balance", opening.toPlainString());
            a.put("bank_stated_closing", bankClosing.toPlainString());
            a.put("ledger_derived_closing", derived.toPlainString());
            a.put("difference", derived.subtract(bankClosing).toPlainString());
            a.put("transaction_count", (long) txns.size());
            accounts.put(acct, a);

            List<BalancePoint> pts = points.stream()
                    .filter(p -> p.accountLast4().equals(acct))
                    .sorted(Comparator.comparing(BalancePoint::asOf))
                    .toList();

            for (int i = 0; i + 1 < pts.size(); i++) {
                BalancePoint p0 = pts.get(i);
                BalancePoint p1 = pts.get(i + 1);

                BigDecimal bankDelta = p1.statedBalance().subtract(p0.statedBalance());

                BigDecimal ledgerDelta = ZERO;
                for (NormalizedTxn t : txns) {
                    if (p0.asOf().isBefore(t.occurredAt())
                            && !t.occurredAt().isAfter(p1.asOf())) {
                        ledgerDelta = t.direction() == Direction.DEBIT
                                ? ledgerDelta.subtract(t.amount())
                                : ledgerDelta.add(t.amount());
                    }
                }

                BigDecimal diff = ledgerDelta.subtract(bankDelta);
                if (diff.signum() == 0) continue;

                // diff > 0 means the bank's balance fell further than the
                // ledger can explain - a debit with no message behind it.
                boolean missingDebit = diff.signum() > 0;
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("account_last4", acct);
                d.put("from_at", p0.asOf().toString());
                d.put("to_at", p1.asOf().toString());
                d.put("amount", diff.abs().toPlainString());
                d.put("kind", missingDebit ? "unexplained_debit" : "unexplained_credit");
                d.put("bank_movement",
                        bankDelta.toPlainString() + " between the two statements");
                d.put("ledger_movement", ledgerDelta.toPlainString()
                        + " across the same window");
                d.put("note", (missingDebit
                        ? "Money left " + acct + " during this window but no message"
                          + " in the corpus evidences it. The bank's stated balance"
                          + " fell while the ledger stays higher - a message is"
                          + " missing, not a parser error."
                        : "Money arrived in " + acct + " during this window but no"
                          + " message in the corpus evidences it."));
                discrepancies.add(d);
            }
        }

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("accounts", accounts);
        doc.put("discrepancies", discrepancies);
        return doc;
    }

    /** What the bank is believed to say about one account's opening/closing. */
    public record AccountMeta(String openingBalance, String closingBalance) {}

    public static Map<Category, BigDecimal> byCategory(List<NormalizedTxn> ledger) {
        Map<Category, BigDecimal> out = new LinkedHashMap<>();
        for (Category c : Category.values()) out.put(c, ZERO);
        for (NormalizedTxn t : ledger) {
            out.put(t.category(), out.get(t.category()).add(t.amount()));
        }
        return out;
    }
}