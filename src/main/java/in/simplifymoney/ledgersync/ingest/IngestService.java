package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.Dates;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.BalancePoint;
import in.simplifymoney.ledgersync.store.LedgerIdentity;
import in.simplifymoney.ledgersync.store.LedgerStore;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Reads a corpus of raw messages and puts transactions in the ledger.
 *
 * One transaction is not one message. Messages that evidence the same real
 * transaction - the same money on the same account at the same minute - are
 * merged here (a phone re-reads its inbox; SMS and email both fire for the
 * same payment). Saving is idempotent, so a corpus can be re-ingested safely.
 *
 * Categories: MICRO is any UPI debit of 100 or less, SPEND/INCOME the rest,
 * and a pair of same-amount, same-merchant, counter-direction legs between
 * the user's own accounts within 30 minutes is a TRANSFER, which is neither
 * spending nor income.
 */
public final class IngestService {

    /** How far apart two legs may be and still be one money movement. */
    private static final long TRANSFER_WINDOW_MINUTES = 30;

    /** A ₹100-or-less debit. */
    private static final BigDecimal MICRO_THRESHOLD =
            new BigDecimal("100.00");

    private final Parsers parsers;
    private final LedgerStore store;

    public IngestService(Parsers parsers, LedgerStore store) {
        this.parsers = parsers;
        this.store = store;
    }

    public Stats ingestFile(Path corpus) throws IOException {
        List<RawMessage> messages = readCorpus(corpus);

        Map<String, Candidate> byIdentity = new LinkedHashMap<>();
        List<BalancePoint> points = new ArrayList<>();
        int parsed = 0;
        int skipped = 0;

        for (RawMessage m : messages) {
            Optional<ParsedTxn> p = parsers.parse(m);
            if (p.isEmpty()) {
                skipped++;
                continue;
            }
            parsed++;

            ParsedTxn txn = p.get();
            OffsetDateTime ist = txn.occurredAt()
                    .withOffsetSameInstant(Dates.IST).truncatedTo(ChronoUnit.MINUTES);
            String key = txn.accountLast4() + "|" + ist + "|"
                    + txn.direction().name() + "|" + txn.amount().toPlainString();

            Candidate c = byIdentity.get(key);
            if (c == null) {
                c = new Candidate(txn.accountLast4(), txn.occurredAt(),
                        txn.direction(), txn.amount(), txn.merchant().trim());
                byIdentity.put(key, c);
            }
            c.ids.add(txn.sourceMessageId());

            if (txn.statedBalance() != null) {
                points.add(new BalancePoint(txn.accountLast4(),
                        txn.occurredAt(), txn.statedBalance()));
            }
        }

        markTransfers(new ArrayList<>(byIdentity.values()));

        int written = 0;
        for (Candidate c : byIdentity.values()) {
            Category category = categoryOf(c);
            if (c.isTransfer) category = Category.TRANSFER;
            store.upsert(new NormalizedTxn(c.acct, c.at, c.dir, c.amount,
                    category, c.merchant, List.copyOf(c.ids)));
            written++;
        }
        for (BalancePoint b : points) store.saveBalancePoint(b);

        return new Stats(messages.size(), written, skipped);
    }

    private Category categoryOf(Candidate c) {
        if (c.dir == Direction.CREDIT) return Category.INCOME;
        if (c.merchant.startsWith("UPI")
                && c.amount.compareTo(MICRO_THRESHOLD) <= 0) return Category.MICRO;
        return Category.SPEND;
    }

    private void markTransfers(List<Candidate> all) {
        List<Candidate> debits = all.stream()
                .filter(c -> c.dir == Direction.DEBIT).toList();
        List<Candidate> credits = all.stream()
                .filter(c -> c.dir == Direction.CREDIT).toList();

        for (Candidate d : debits) {
            for (Candidate c : credits) {
                if (c.acct.equals(d.acct)) continue;         // not between own accounts
                if (c.amount.compareTo(d.amount) != 0) continue;
                if (!c.merchant.equals(d.merchant)) continue;
                long gap = Math.abs(ChronoUnit.MINUTES.between(
                        d.at.truncatedTo(ChronoUnit.MINUTES),
                        c.at.truncatedTo(ChronoUnit.MINUTES)));
                if (gap > TRANSFER_WINDOW_MINUTES) continue;
                d.isTransfer = true;
                c.isTransfer = true;
            }
        }
    }

    public static List<RawMessage> readCorpus(Path corpus) throws IOException {
        List<RawMessage> out = new ArrayList<>();
        try (Stream<String> lines = Files.lines(corpus)) {
            for (String line : (Iterable<String>) lines.filter(s -> !s.isBlank())::iterator) {
                Map<String, Object> o = Json.parseObject(line);
                out.add(new RawMessage(
                        (String) o.get("message_id"),
                        (String) o.get("channel"),
                        (String) o.get("sender"),
                        OffsetDateTime.parse((String) o.get("received_at")),
                        (String) o.get("device_id"),
                        (String) o.get("body")));
            }
        }
        return out;
    }

    private static final class Candidate {
        final String acct;
        final OffsetDateTime at;
        final Direction dir;
        final BigDecimal amount;
        final String merchant;
        final TreeSet<String> ids = new TreeSet<>();
        boolean isTransfer;

        Candidate(String acct, OffsetDateTime at, Direction dir,
                  BigDecimal amount, String merchant) {
            this.acct = acct;
            this.at = at;
            this.dir = dir;
            this.amount = amount;
            this.merchant = merchant;
        }
    }

    public record Stats(int messagesRead, int transactionsWritten, int messagesSkipped) {}
}