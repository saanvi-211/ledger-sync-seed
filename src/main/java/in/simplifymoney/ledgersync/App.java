package in.simplifymoney.ledgersync;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.report.Reports;
import in.simplifymoney.ledgersync.store.SqlLedgerStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Command line entry point.
 *
 *   migrate                  apply db/migration/*.sql
 *   ingest  <corpus.jsonl>   read a corpus into the ledger
 *   report  <out-dir>        write ledger.json, summary.json, reconciliation.json
 */
public final class App {

    private static final Path DB = Path.of("data", "ledger");
    private static final Path MIGRATIONS = Path.of("db", "migration");
    private static final Path TOTALS = Path.of("fixtures", "corpus-a-totals.json");

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: migrate | ingest <corpus.jsonl> | report <out-dir>");
            System.exit(2);
        }
        Files.createDirectories(DB.getParent());

        switch (args[0]) {
            case "migrate" -> {
                try (SqlLedgerStore store = new SqlLedgerStore(DB)) {
                    store.migrate(MIGRATIONS);
                    System.out.println("ledger rows: " + store.count());
                }
            }
            case "ingest" -> {
                if (args.length < 2) throw new IllegalArgumentException("ingest needs a corpus");
                try (SqlLedgerStore store = new SqlLedgerStore(DB)) {
                    store.migrate(MIGRATIONS);
                    var stats = new IngestService(new Parsers(), store)
                            .ingestFile(Path.of(args[1]));
                    System.out.println(stats);
                    System.out.println("ledger rows: " + store.count());
                }
            }
            case "report" -> {
                if (args.length < 2) throw new IllegalArgumentException("report needs a directory");
                Path out = Path.of(args[1]);
                Files.createDirectories(out);
                try (SqlLedgerStore store = new SqlLedgerStore(DB)) {
                    var ledger = store.all();
                    var meta = loadAccountMeta(TOTALS);
                    Files.writeString(out.resolve("ledger.json"),
                            Json.writePretty(Reports.ledgerDocument(ledger)));
                    Files.writeString(out.resolve("summary.json"),
                            Json.writePretty(Reports.summary(ledger)));
                    Files.writeString(out.resolve("reconciliation.json"),
                            Json.writePretty(Reports.reconciliation(ledger,
                                    store.balancePoints(), meta)));
                    System.out.println("wrote 3 files to " + out);
                }
            }
            default -> {
                System.err.println("unknown command: " + args[0]);
                System.exit(2);
            }
        }
    }

    private static Map<String, Reports.AccountMeta> loadAccountMeta(Path totals)
            throws java.io.IOException {
        Map<String, Reports.AccountMeta> meta = new LinkedHashMap<>();
        if (!Files.exists(totals)) return meta;
        Map<String, Object> doc = Json.parseObject(Files.readString(totals));
        @SuppressWarnings("unchecked")
        Map<String, Object> accounts = (Map<String, Object>) doc.get("accounts");
        for (Map.Entry<String, Object> e : accounts.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> a = (Map<String, Object>) e.getValue();
            meta.put(e.getKey(), new Reports.AccountMeta(
                    (String) a.get("opening_balance"),
                    (String) a.get("closing_balance")));
        }
        return meta;
    }
}
