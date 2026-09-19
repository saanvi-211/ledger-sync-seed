package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

/**
 * The store this service has used since it was written: a single relational
 * table, reached over plain JDBC.
 *
 * The driver is a runtime dependency (see build.gradle) - this class compiles
 * against the JDK alone.
 */
public final class SqlLedgerStore implements LedgerStore, AutoCloseable {

    private static final String URL_PREFIX = "jdbc:h2:";
    private final Connection conn;

    public SqlLedgerStore(Path dbFile) {
        try {
            this.conn = DriverManager.getConnection(
                    URL_PREFIX + dbFile.toAbsolutePath() + ";MODE=PostgreSQL", "sa", "");
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "could not open the ledger database at " + dbFile
                            + " (is the H2 driver on the runtime classpath?)", e);
        }
    }

    /** Applies every db/migration/V*.sql in filename order. */
    public void migrate(Path migrationDir) {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS schema_history ("
                    + "  filename VARCHAR(200) PRIMARY KEY,"
                    + "  applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");

            List<Path> files;
            try (var s = Files.list(migrationDir)) {
                files = s.filter(p -> p.getFileName().toString().endsWith(".sql")).sorted().toList();
            }
            for (Path f : files) {
                String name = f.getFileName().toString();
                try (PreparedStatement q = conn.prepareStatement(
                        "SELECT 1 FROM schema_history WHERE filename = ?")) {
                    q.setString(1, name);
                    try (ResultSet rs = q.executeQuery()) {
                        if (rs.next()) continue;
                    }
                }
                String sql = Files.readString(f);
                for (String stmt : sql.split(";")) {
                    if (!stmt.isBlank()) st.execute(stmt);
                }
                try (PreparedStatement ins = conn.prepareStatement(
                        "INSERT INTO schema_history(filename) VALUES (?)")) {
                    ins.setString(1, name);
                    ins.executeUpdate();
                }
                System.out.println("applied " + name);
            }
        } catch (Exception e) {
            throw new IllegalStateException("migration failed", e);
        }
    }

    @Override
    public void save(NormalizedTxn t) {
        insert(t, LedgerIdentity.of(t));
    }

    @Override
    public void upsert(NormalizedTxn t) {
        String key = LedgerIdentity.of(t);
        String merged = null;
        try (PreparedStatement q = conn.prepareStatement(
                "SELECT source_message_ids FROM ledger WHERE identity_key = ?")) {
            q.setString(1, key);
            try (ResultSet rs = q.executeQuery()) {
                if (rs.next()) {
                    TreeSet<String> ids = new TreeSet<>(
                            Arrays.stream(rs.getString(1).split(","))
                                    .filter(s -> !s.isBlank()).toList());
                    ids.addAll(t.sourceMessageIds());
                    merged = String.join(",", ids);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read existing " + t, e);
        }
        if (merged == null) {
            insert(t, key);
            return;
        }
        try (PreparedStatement up = conn.prepareStatement(
                "UPDATE ledger SET category = ?, merchant = ?, source_message_ids = ?"
                        + " WHERE identity_key = ?")) {
            up.setString(1, t.category().name());
            up.setString(2, t.merchant());
            up.setString(3, merged);
            up.setString(4, key);
            up.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not merge " + t, e);
        }
    }

    private void insert(NormalizedTxn t, String key) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO ledger(account_last4, occurred_at, direction, amount,"
                        + " category, merchant, source_message_ids, identity_key)"
                        + " VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setString(1, t.accountLast4());
            ps.setString(2, t.occurredAt().toString());
            ps.setString(3, t.direction().name());
            ps.setBigDecimal(4, t.amount());
            ps.setString(5, t.category().name());
            ps.setString(6, t.merchant());
            ps.setString(7, String.join(",", t.sourceMessageIds()));
            ps.setString(8, key);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not save " + t, e);
        }
    }

    @Override
    public void saveBalancePoint(BalancePoint p) {
        try (PreparedStatement ps = conn.prepareStatement(
                "MERGE INTO balance_points (account_last4, as_of, stated_balance)"
                        + " KEY(account_last4, as_of, stated_balance) VALUES (?,?,?)")) {
            ps.setString(1, p.accountLast4());
            ps.setString(2, p.asOf().toString());
            ps.setBigDecimal(3, p.statedBalance());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not save balance point", e);
        }
    }

    @Override
    public List<BalancePoint> balancePoints() {
        List<BalancePoint> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT account_last4, as_of, stated_balance FROM balance_points")) {
            while (rs.next()) {
                out.add(new BalancePoint(rs.getString(1),
                        OffsetDateTime.parse(rs.getString(2)),
                        rs.getBigDecimal(3).setScale(2)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read balance points", e);
        }
        return out;
    }

    @Override
    public List<NormalizedTxn> all() {
        List<NormalizedTxn> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT account_last4, occurred_at, direction, amount, category,"
                             + " merchant, source_message_ids FROM ledger ORDER BY occurred_at")) {
            while (rs.next()) {
                out.add(new NormalizedTxn(
                        rs.getString(1),
                        OffsetDateTime.parse(rs.getString(2)),
                        Direction.valueOf(rs.getString(3)),
                        rs.getBigDecimal(4).setScale(2),
                        Category.valueOf(rs.getString(5)),
                        rs.getString(6),
                        Arrays.stream(rs.getString(7).split(","))
                                .filter(s -> !s.isBlank()).toList()));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the ledger", e);
        }
        return out;
    }

    @Override
    public long count() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM ledger")) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new IllegalStateException("could not count the ledger", e);
        }
    }

    public BigDecimal sumAmounts() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT SUM(amount) FROM ledger")) {
            return rs.next() && rs.getBigDecimal(1) != null
                    ? rs.getBigDecimal(1).setScale(2) : BigDecimal.ZERO.setScale(2);
        } catch (SQLException e) {
            throw new IllegalStateException("could not total the ledger", e);
        }
    }

    @Override
    public void close() {
        try { conn.close(); } catch (SQLException ignored) { }
    }
}
