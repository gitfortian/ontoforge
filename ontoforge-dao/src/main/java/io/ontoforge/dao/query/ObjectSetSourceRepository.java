package io.ontoforge.dao.query;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Bounded ObjectSet source scans and live permission checks; no ontology dependencies. */
@Repository
public class ObjectSetSourceRepository {
    private static final Pattern IDENT = Pattern.compile("[a-z][a-z0-9_]{0,62}");
    private static final int MAX_SOURCE_BYTES = 8 * 1024 * 1024;
    private static final int QUERY_TIMEOUT_SECONDS = 3;
    public record SourceRef(String sourceSchema, String sourceTable, String sourceKey) {}
    public record ScanLimits(int maxPhysicalRows, long maxSourceBytes, int timeoutSeconds) {}
    public static final class SourceFailure extends RuntimeException {
        private final String code;
        private final String field;
        public SourceFailure(String code, String message, String field) {
            super(message); this.code = code; this.field = field;
        }
        public String code() { return code; }
        public String field() { return field; }
    }
    public RawScan scan(JdbcTemplate source, SourceRef binding, List<String> columns, ScanLimits limits) {
        return source.execute((ConnectionCallback<RawScan>) connection -> executeScan(connection, binding, columns, limits));
    }
    public record RawScan(List<Map<String, Object>> rows, List<String> columns,
            Instant startedAt, Instant completedAt, long sourceBytes, long deadlineNanos) {}
    private RawScan executeScan(Connection connection, SourceRef binding,
            List<String> columns, ScanLimits limits) throws SQLException {
        boolean auto = connection.getAutoCommit();
        int isolation = connection.getTransactionIsolation();
        boolean readOnly = connection.isReadOnly();
        Instant started = Instant.now();
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(limits.timeoutSeconds());
        try {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                if (System.nanoTime() >= deadlineNanos) {
                    throw fail("SOURCE_TIMEOUT", "来源查询超过本次请求的端到端时间预算；未返回部分结果", "source");
                }
                statement.setQueryTimeout(limits.timeoutSeconds());
                statement.execute("SET TRANSACTION READ ONLY");
                if (System.nanoTime() >= deadlineNanos) {
                    throw fail("SOURCE_TIMEOUT", "来源查询超过本次请求的端到端时间预算；未返回部分结果", "source");
                }
                long remainingMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
                statement.setQueryTimeout((int) Math.max(1, Math.min(limits.timeoutSeconds(), (remainingMillis + 999) / 1000)));
                statement.execute("SET LOCAL statement_timeout = '" + limits.timeoutSeconds() + "s'");
            }
            verifySourceReadable(connection, binding, deadlineNanos);
            String projection = String.join(", ", columns.stream().map(ObjectSetSourceRepository::qi).toList());
            String from = " FROM " + qt(binding.sourceSchema(), binding.sourceTable());
            String sizeExpr = columns.stream()
                    .map(c -> "COALESCE(octet_length(" + qi(c) + "::text),0)::bigint")
                    .reduce((a,b) -> a + " + " + b).orElse("0");
            String budgetSql = "WITH candidates AS (SELECT " + projection + from
                    + " ORDER BY " + qi(binding.sourceKey()) + " LIMIT " + (limits.maxPhysicalRows() + 1) + ") "
                    + "SELECT COUNT(*)::bigint AS row_count, COALESCE(SUM(" + sizeExpr
                    + "),0)::bigint AS source_bytes FROM candidates";
            long rowCount;
            long sourceBytes;
            try (PreparedStatement statement = connection.prepareStatement(budgetSql)) {
                applyRemainingTimeout(connection, statement, deadlineNanos);
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) throw new SQLException("M7 source budget query returned no row");
                    rowCount = rs.getLong("row_count");
                    sourceBytes = rs.getLong("source_bytes");
                }
            }
            if (rowCount > limits.maxPhysicalRows()) throw fail("QUERY_BUDGET_EXCEEDED",
                    "来源物理行数超过本次请求的 " + limits.maxPhysicalRows() + " 行预算；没有返回部分结果", "source");
            if (sourceBytes > limits.maxSourceBytes()) {
                String sourceBudgetLabel = limits.maxSourceBytes() == MAX_SOURCE_BYTES
                        ? "8 MiB" : limits.maxSourceBytes() + " 字节";
                throw fail("QUERY_BUDGET_EXCEEDED",
                        "本次查询来源扫描超过 " + sourceBudgetLabel + " 预算；没有返回部分结果", "source");
            }
            String sql = "SELECT " + projection + from + " ORDER BY " + qi(binding.sourceKey())
                    + " LIMIT " + (limits.maxPhysicalRows() + 1);
            List<Map<String,Object>> rows = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                applyRemainingTimeout(connection, statement, deadlineNanos);
                statement.setFetchSize(50);
                statement.setMaxRows(limits.maxPhysicalRows() + 1);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        if (System.nanoTime() >= deadlineNanos) {
                            throw fail("SOURCE_TIMEOUT", "来源查询超过本次请求的端到端时间预算；未返回部分结果", "source");
                        }
                        Map<String,Object> row = new LinkedHashMap<>();
                        for (String column : columns) row.put(column, rs.getObject(column));
                        rows.add(row);
                    }
                }
            }
            if (rows.size() > limits.maxPhysicalRows()) throw fail("QUERY_BUDGET_EXCEEDED",
                    "来源物理行数超过本次请求的 " + limits.maxPhysicalRows() + " 行预算；没有返回部分结果", "source");
            Instant completed = Instant.now();
            connection.commit();
            return new RawScan(List.copyOf(rows), List.copyOf(columns), started, completed, sourceBytes, deadlineNanos);
        } catch (RuntimeException | SQLException ex) {
            try { connection.rollback(); } catch (SQLException ignored) {}
            if (ex instanceof RuntimeException runtime) throw runtime;
            throw (SQLException) ex;
        } finally {
            try { connection.setAutoCommit(auto); } catch (SQLException ignored) {}
            try { connection.setTransactionIsolation(isolation); } catch (SQLException ignored) {}
            try { connection.setReadOnly(readOnly); } catch (SQLException ignored) {}
        }
    }

    public void verifyReadable(JdbcTemplate source, SourceRef binding, long deadlineNanos) {
        if (deadlineNanos <= 0) {
            source.execute((ConnectionCallback<Void>) connection -> {
                verifySourceReadable(connection, binding);
                return null;
            });
            return;
        }
        source.execute((ConnectionCallback<Void>) connection -> {
            boolean autoCommit = connection.getAutoCommit();
            int isolation = connection.getTransactionIsolation();
            boolean readOnly = connection.isReadOnly();
            try {
                connection.setReadOnly(true);
                connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    if (System.nanoTime() >= deadlineNanos) {
                        throw fail("SOURCE_TIMEOUT", "来源查询超过本次请求的端到端时间预算；未返回部分结果", "source");
                    }
                    long remainingMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
                    statement.setQueryTimeout((int) Math.max(1, Math.min(QUERY_TIMEOUT_SECONDS, (remainingMillis + 999) / 1000)));
                    statement.execute("SET TRANSACTION READ ONLY");
                }
                verifySourceReadable(connection, binding, deadlineNanos);
                connection.commit();
                return null;
            } catch (RuntimeException | SQLException ex) {
                try { connection.rollback(); } catch (SQLException ignored) {}
                if (ex instanceof RuntimeException runtime) throw runtime;
                throw (SQLException) ex;
            } finally {
                try { connection.setAutoCommit(autoCommit); } catch (SQLException ignored) {}
                try { connection.setTransactionIsolation(isolation); } catch (SQLException ignored) {}
                try { connection.setReadOnly(readOnly); } catch (SQLException ignored) {}
            }
        });
    }

    private void verifySourceReadable(Connection connection, SourceRef binding) throws SQLException {
        verifySourceReadable(connection, binding, 0L);
    }

    private void verifySourceReadable(Connection connection, SourceRef binding,
            long deadlineNanos) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT has_schema_privilege(current_user, ?, 'USAGE') AS schema_usable,
                       to_regclass(quote_ident(?) || '.' || quote_ident(?)) IS NOT NULL AS relation_exists,
                       CASE WHEN to_regclass(quote_ident(?) || '.' || quote_ident(?)) IS NULL THEN FALSE
                            ELSE has_table_privilege(current_user,
                                 to_regclass(quote_ident(?) || '.' || quote_ident(?)), 'SELECT') END AS can_select,
                       COALESCE((SELECT relrowsecurity OR relforcerowsecurity
                                   FROM pg_catalog.pg_class
                                  WHERE oid=to_regclass(quote_ident(?) || '.' || quote_ident(?))), FALSE) AS row_security
                """)) {
            if (deadlineNanos > 0) applyRemainingTimeout(connection, statement, deadlineNanos);
            else statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.setString(1, binding.sourceSchema());
            statement.setString(2, binding.sourceSchema()); statement.setString(3, binding.sourceTable());
            statement.setString(4, binding.sourceSchema()); statement.setString(5, binding.sourceTable());
            statement.setString(6, binding.sourceSchema()); statement.setString(7, binding.sourceTable());
            statement.setString(8, binding.sourceSchema()); statement.setString(9, binding.sourceTable());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next() || !rs.getBoolean("schema_usable")) {
                    throw fail("SOURCE_UNAVAILABLE", "来源不可用或当前只读账号没有 schema USAGE 权限；拒绝返回结果", "source");
                }
                if (!rs.getBoolean("relation_exists")) {
                    throw fail("BINDING_SCHEMA_DRIFT", "绑定来源表已不存在；拒绝返回冻结结果", "sourceTable");
                }
                if (!rs.getBoolean("can_select")) {
                    throw fail("SOURCE_UNAVAILABLE", "来源当前没有 SELECT 权限；拒绝返回结果", "source");
                }
                // Local bounded bindings do not pin row-security policy/role revisions.
                // Even a table owner can bypass RLS, so reject the unsupported source instead of
                // silently changing the meaning of a frozen full-resource read after a policy change.
                if (rs.getBoolean("row_security")) {
                    throw fail("SOURCE_NOT_ALLOWED", "来源不符合当前隔离只读访问条件；拒绝返回结果", "source");
                }
            }
        }
    }

    private void applyRemainingTimeout(Connection connection, PreparedStatement statement,
            long deadlineNanos) throws SQLException {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            throw fail("SOURCE_TIMEOUT", "来源查询超过本次请求的端到端时间预算；未返回部分结果", "source");
        }
        // Reserve a small margin for the SET LOCAL round-trip so the next SQL statement
        // cannot inherit a timeout that extends beyond the original end-to-end deadline.
        long remainingMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos) - 10);
        int jdbcTimeoutSeconds = (int) Math.max(1, Math.min(QUERY_TIMEOUT_SECONDS, (remainingMillis + 999) / 1000));
        try (PreparedStatement timeout = connection.prepareStatement("SELECT set_config('statement_timeout', ?, true)")) {
            timeout.setQueryTimeout(jdbcTimeoutSeconds);
            timeout.setString(1, remainingMillis + "ms");
            timeout.execute();
        }
        long afterConfigNanos = deadlineNanos - System.nanoTime();
        if (afterConfigNanos <= 0) {
            throw fail("SOURCE_TIMEOUT", "来源查询超过本次请求的端到端时间预算；未返回部分结果", "source");
        }
        long jdbcRemainingMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(afterConfigNanos));
        statement.setQueryTimeout((int) Math.max(1,
                Math.min(QUERY_TIMEOUT_SECONDS, (jdbcRemainingMillis + 999) / 1000)));
    }

    private static String qi(String identifier) {
        if (identifier == null || !IDENT.matcher(identifier).matches()) throw new IllegalArgumentException("invalid identifier");
        return "\"" + identifier + "\"";
    }

    private static String qt(String schema, String table) { return qi(schema) + "." + qi(table); }

    private static SourceFailure fail(String code, String message, String field) {
        return new SourceFailure(code, message, field);
    }
}
