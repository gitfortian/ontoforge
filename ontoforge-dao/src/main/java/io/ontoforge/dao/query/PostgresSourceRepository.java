package io.ontoforge.dao.query;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Repository;

/** PostgreSQL source metadata and bounded repeatable-read I/O. No ontology or authorization imports. */
@Repository
public class PostgresSourceRepository {
    private static final String COLUMNS = """
                    SELECT c.column_name, c.data_type, c.udt_name, c.is_nullable, c.ordinal_position,
                           c.character_maximum_length, c.numeric_precision, c.numeric_scale, c.datetime_precision,
                           EXISTS (
                             SELECT 1
                               FROM pg_catalog.pg_constraint pc
                               JOIN pg_catalog.pg_class tbl ON tbl.oid=pc.conrelid
                               JOIN pg_catalog.pg_namespace ns ON ns.oid=tbl.relnamespace
                               JOIN pg_catalog.pg_attribute a ON a.attrelid=tbl.oid AND a.attnum=ANY(pc.conkey)
                              WHERE ns.nspname=c.table_schema AND tbl.relname=c.table_name
                                AND a.attname=c.column_name AND pc.contype='p'
                                AND array_length(pc.conkey,1)=1
                           ) AS is_primary,
                           EXISTS (
                             SELECT 1
                               FROM pg_catalog.pg_constraint pc
                               JOIN pg_catalog.pg_class tbl ON tbl.oid=pc.conrelid
                               JOIN pg_catalog.pg_namespace ns ON ns.oid=tbl.relnamespace
                               JOIN pg_catalog.pg_attribute a ON a.attrelid=tbl.oid AND a.attnum=ANY(pc.conkey)
                              WHERE ns.nspname=c.table_schema AND tbl.relname=c.table_name
                                AND a.attname=c.column_name AND pc.contype='u'
                                AND array_length(pc.conkey,1)=1
                           ) AS is_unique
                      FROM information_schema.columns c
                     WHERE c.table_schema=? AND c.table_name=?
                     ORDER BY c.ordinal_position
                    """;
    private static final String KEYS = """
                    SELECT pc.conname,
                           CASE pc.contype WHEN 'p' THEN 'PRIMARY KEY' ELSE 'UNIQUE' END AS constraint_type,
                           a.attname AS column_name, key.ordinality::int AS ordinal_position
                      FROM pg_catalog.pg_constraint pc
                      JOIN pg_catalog.pg_class tbl ON tbl.oid=pc.conrelid
                      JOIN pg_catalog.pg_namespace ns ON ns.oid=tbl.relnamespace
                      JOIN LATERAL unnest(pc.conkey) WITH ORDINALITY AS key(attnum, ordinality) ON TRUE
                      JOIN pg_catalog.pg_attribute a ON a.attrelid=tbl.oid AND a.attnum=key.attnum
                     WHERE ns.nspname=? AND tbl.relname=? AND pc.contype IN ('p','u')
                     ORDER BY pc.conname, key.ordinality
                    """;
    private static final String PERMISSIONS = """
                SELECT has_schema_privilege(current_user, ?, 'USAGE') AS schema_usable,
                       relation.oid IS NOT NULL AS relation_exists,
                       CASE WHEN relation.oid IS NULL THEN FALSE
                            ELSE has_table_privilege(current_user, relation.oid, 'SELECT') END AS can_select
                  FROM (SELECT to_regclass(quote_ident(?) || '.' || quote_ident(?)) AS oid) target
                  LEFT JOIN pg_catalog.pg_class relation ON relation.oid = target.oid
                """;

    public record SourceColumn(String name,String dataType,String udtName,boolean nullable,
            int ordinalPosition,boolean primaryKey,boolean uniqueKey,Integer characterMaximumLength,
            Integer numericPrecision,Integer numericScale,Integer datetimePrecision) {}
    public record KeyColumn(String constraintName,String constraintType,String columnName,int ordinalPosition) {}
    public record Metadata(List<SourceColumn> columns,List<KeyColumn> keys) {}
    public record ReadPlan(String schema,String table,String keyColumn,List<String> columns,
                           String keyType,Object key,int maxRows,long maxBytes,int timeoutSeconds) {
        public ReadPlan { columns=List.copyOf(columns); }
    }
    public record SourceRead(List<Map<String,Object>> rows,Metadata metadata,List<String> columns,
                             Instant startedAt,Instant completedAt) {}
    public static final class ReadFailure extends RuntimeException {
        private final String code,field;
        public ReadFailure(String code,String message,String field) { super(message);this.code=code;this.field=field; }
        public String code() { return code; }
        public String field() { return field; }
    }
    private static String qi(String id) {
        if(id==null||!id.matches("[a-z][a-z0-9_]{0,62}"))throw new IllegalArgumentException("Unsafe source identifier");
        return "\""+id+"\"";
    }
    private static String qt(String table) { return "\"public\"."+qi(table); }
    public List<String> listTables(JdbcTemplate source,List<String> allowedTables) {
        if(allowedTables.isEmpty())return List.of();
        String placeholders=String.join(",",Collections.nCopies(allowedTables.size(),"?"));
        return source.query("SELECT table_name FROM information_schema.tables WHERE table_schema='public' "
                +"AND table_type='BASE TABLE' AND table_name IN ("+placeholders+") ORDER BY table_name",
                (rs,i)->rs.getString(1),allowedTables.toArray());
    }
    /** Inspect metadata within the original caller deadline, in a read-only transaction. */
    public Metadata inspectTableWithDeadline(JdbcTemplate source, String schema, String table, long deadlineNanos) {
        return source.execute((ConnectionCallback<Metadata>) connection -> {
            boolean auto = connection.getAutoCommit();
            int isolation = connection.getTransactionIsolation();
            boolean readOnly = connection.isReadOnly();
            try {
                connection.setReadOnly(true);
                connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    statement.setQueryTimeout(remainingQueryTimeoutSeconds(deadlineNanos));
                    if (System.nanoTime() >= deadlineNanos) {
                        throw new ReadFailure("SOURCE_TIMEOUT", "来源结构复核超过本次请求的端到端时间预算", "source");
                    }
                    statement.execute("SET TRANSACTION READ ONLY");
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT 1 FROM information_schema.tables
                         WHERE table_schema=? AND table_name=? AND table_type='BASE TABLE'
                        """)) {
                    applyRemainingTimeout(connection, statement, deadlineNanos);
                    statement.setString(1, schema);
                    statement.setString(2, table);
                    try (ResultSet rs = statement.executeQuery()) {
                        if (!rs.next()) {
                            throw new ReadFailure("BINDING_SCHEMA_DRIFT",
                                    "Binding 来源表不在当前服务端允许列表或已不存在", "sourceTable");
                        }
                    }
                }
                List<SourceColumn> columns = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement(COLUMNS)) {
                    applyRemainingTimeout(connection, statement, deadlineNanos);
                    statement.setString(1, schema);
                    statement.setString(2, table);
                    try (ResultSet rs = statement.executeQuery()) {
                        while (rs.next()) {
                            columns.add(new SourceColumn(rs.getString("column_name"), rs.getString("data_type"),
                                    rs.getString("udt_name"), "YES".equals(rs.getString("is_nullable")),
                                    rs.getInt("ordinal_position"), rs.getBoolean("is_primary"), rs.getBoolean("is_unique"),
                                    rs.getObject("character_maximum_length", Integer.class),
                                    rs.getObject("numeric_precision", Integer.class), rs.getObject("numeric_scale", Integer.class),
                                    rs.getObject("datetime_precision", Integer.class)));
                        }
                    }
                }
                if (columns.isEmpty()) {
                    throw new ReadFailure("BINDING_SCHEMA_DRIFT", "Binding 来源表结构不可见或已不存在", "sourceTable");
                }
                List<KeyColumn> keys = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement(KEYS)) {
                    applyRemainingTimeout(connection, statement, deadlineNanos);
                    statement.setString(1, schema);
                    statement.setString(2, table);
                    try (ResultSet rs = statement.executeQuery()) {
                        while (rs.next()) {
                            keys.add(new KeyColumn(rs.getString(1), rs.getString(2),
                                    rs.getString(3), rs.getInt(4)));
                        }
                    }
                }
                connection.commit();
                return new Metadata(List.copyOf(columns), List.copyOf(keys));
            } catch (RuntimeException | SQLException ex) {
                try { connection.rollback(); } catch (SQLException ignored) {}
                if (ex instanceof RuntimeException runtime) throw runtime;
                throw (SQLException) ex;
            } finally {
                try { connection.setAutoCommit(auto); } catch (SQLException ignored) {}
                try { connection.setTransactionIsolation(isolation); } catch (SQLException ignored) {}
                try { connection.setReadOnly(readOnly); } catch (SQLException ignored) {}
            }
        });
    }

    private static int remainingQueryTimeoutSeconds(long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) return 1;
        long millis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining));
        return (int) Math.max(1, Math.min(3, (millis + 999) / 1000));
    }

    private static void applyRemainingTimeout(Connection connection, PreparedStatement statement,
            long deadlineNanos) throws SQLException {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) throw new SQLTimeoutException("M7 source deadline expired");
        long remainingMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos) - 10);
        int timeoutSeconds = (int) Math.max(1, Math.min(3, (remainingMillis + 999) / 1000));
        try (PreparedStatement timeout = connection.prepareStatement("SELECT set_config('statement_timeout', ?, true)")) {
            timeout.setQueryTimeout(timeoutSeconds);
            timeout.setString(1, remainingMillis + "ms");
            timeout.execute();
        }
        long afterConfig = deadlineNanos - System.nanoTime();
        if (afterConfig <= 0) throw new SQLTimeoutException("M7 source deadline expired");
        long afterMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(afterConfig));
        statement.setQueryTimeout((int) Math.max(1, Math.min(3, (afterMillis + 999) / 1000)));
    }

    public Metadata metadata(JdbcTemplate jdbc,String schema,String table) {
        List<SourceColumn> columns=jdbc.query(COLUMNS,(rs,i)->new SourceColumn(rs.getString("column_name"),
            rs.getString("data_type"),rs.getString("udt_name"),"YES".equals(rs.getString("is_nullable")),
            rs.getInt("ordinal_position"),rs.getBoolean("is_primary"),rs.getBoolean("is_unique"),
            rs.getObject("character_maximum_length",Integer.class),rs.getObject("numeric_precision",Integer.class),
            rs.getObject("numeric_scale",Integer.class),rs.getObject("datetime_precision",Integer.class)),schema,table);
        List<KeyColumn> keys=jdbc.query(KEYS,(rs,i)->new KeyColumn(rs.getString(1),rs.getString(2),rs.getString(3),rs.getInt(4)),schema,table);
        return new Metadata(List.copyOf(columns),List.copyOf(keys));
    }
    public <T> List<T> previewRows(JdbcTemplate source,String table,String key,Collection<String> columns,
                                  int limit,RowMapper<T> mapper) {
        if(limit<1||limit>10001)throw new IllegalArgumentException("Source row budget required");
        String projection=String.join(", ",columns.stream().map(PostgresSourceRepository::qi).toList());
        return source.query("SELECT "+projection+" FROM "+qt(table)+" ORDER BY "+qi(key)+" LIMIT "+limit,mapper);
    }
    public SourceRead read(JdbcTemplate source,ReadPlan plan,Consumer<Metadata> validateMetadata) {
        if(!"public".equals(plan.schema())||plan.maxRows()<1||plan.maxRows()>10000||plan.maxBytes()<1||plan.timeoutSeconds()<1)
            throw new IllegalArgumentException("Bounded public source plan required");
        return source.execute((ConnectionCallback<SourceRead>) connection -> executeRead(connection,plan,validateMetadata));
    }
    private SourceRead executeRead(Connection connection,ReadPlan plan,Consumer<Metadata> validator) throws SQLException {
        boolean autoCommit=connection.getAutoCommit(),readOnly=connection.isReadOnly();
        int isolation=connection.getTransactionIsolation();
        try {
            connection.setReadOnly(true);connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);connection.setAutoCommit(false);
            try(Statement statement=connection.createStatement()) { statement.setQueryTimeout(plan.timeoutSeconds());statement.execute("SET TRANSACTION READ ONLY"); }
            Instant startedAt=Instant.now();
            verifyReadable(connection,plan);
            JdbcTemplate metadataJdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
            metadataJdbc.setQueryTimeout(plan.timeoutSeconds());
            Metadata metadata=metadata(metadataJdbc,plan.schema(),plan.table());
            if(metadata.columns().isEmpty())throw new ReadFailure("BINDING_SCHEMA_DRIFT","绑定来源表不存在或不可见；请返回 M5 修复绑定","sourceTable");
            validator.accept(metadata);
            String projection=String.join(", ",plan.columns().stream().map(PostgresSourceRepository::qi).toList());
            String fromWhere=" FROM "+qt(plan.table())+" WHERE "+qi(plan.keyColumn())+" = ?";
            String sizes=plan.columns().stream().map(c->"COALESCE(octet_length("+qi(c)+"::text), 0)::bigint")
                .reduce((a,b)->a+" + "+b).orElse("0");
            String budgetSql="WITH candidates AS (SELECT "+projection+fromWhere+" LIMIT "+(plan.maxRows()+1)+") "
                +"SELECT COUNT(*)::bigint AS row_count,COALESCE(SUM("+sizes+"), 0)::bigint AS source_bytes FROM candidates";
            long rowCount,sourceBytes;
            try(PreparedStatement statement=connection.prepareStatement(budgetSql)) {
                configure(statement,plan);
                try(ResultSet budget=statement.executeQuery()) {
                    if(!budget.next())throw new SQLException("Object Runtime source budget query returned no row");
                    rowCount=budget.getLong("row_count");sourceBytes=budget.getLong("source_bytes");
                }
            }
            if(rowCount>plan.maxRows())throw rowBudget();
            long names=plan.columns().stream().mapToLong(c->c.getBytes(StandardCharsets.UTF_8).length).sum();
            if(sourceBytes+rowCount*names>plan.maxBytes())throw byteBudget();
            List<Map<String,Object>> rows=new ArrayList<>();
            try(PreparedStatement statement=connection.prepareStatement("SELECT "+projection+fromWhere+" ORDER BY "+qi(plan.keyColumn())+" LIMIT "+(plan.maxRows()+1))) {
                configure(statement,plan);statement.setFetchSize(50);statement.setMaxRows(plan.maxRows()+1);
                long scanned=0;
                try(ResultSet rs=statement.executeQuery()) {
                    while(rs.next()) {
                        Map<String,Object> row=new LinkedHashMap<>();
                        for(String column:plan.columns()) {
                            Object value=rs.getObject(column);scanned+=column.getBytes(StandardCharsets.UTF_8).length+estimatedValueBytes(value);
                            if(scanned>plan.maxBytes())throw byteBudget();row.put(column,value);
                        }
                        rows.add(row);
                    }
                }
            }
            if(rows.size()>plan.maxRows())throw rowBudget();
            Instant completedAt=Instant.now();connection.commit();
            return new SourceRead(List.copyOf(rows),metadata,plan.columns(),startedAt,completedAt);
        } catch(RuntimeException|SQLException error) { try { connection.rollback(); } catch(SQLException ignored) {} throw error; }
        finally {
            try { if(connection.getAutoCommit()!=autoCommit)connection.setAutoCommit(autoCommit); } catch(SQLException ignored) {}
            try { if(connection.getTransactionIsolation()!=isolation)connection.setTransactionIsolation(isolation); } catch(SQLException ignored) {}
            try { if(connection.isReadOnly()!=readOnly)connection.setReadOnly(readOnly); } catch(SQLException ignored) {}
        }
    }
    private static void configure(PreparedStatement statement,ReadPlan plan) throws SQLException {
        statement.setQueryTimeout(plan.timeoutSeconds());
        if("INTEGER".equals(plan.keyType()))statement.setLong(1,(Long)plan.key());else statement.setString(1,(String)plan.key());
    }
    private static ReadFailure rowBudget() { return new ReadFailure("QUERY_BUDGET_EXCEEDED","单个对象超过 10000 条来源行预算；没有返回部分对象","key"); }
    private static ReadFailure byteBudget() { return new ReadFailure("QUERY_BUDGET_EXCEEDED","对象来源扫描超过 8 MiB 预算；没有返回部分对象","key"); }
    private static void verifyReadable(Connection connection,ReadPlan plan) throws SQLException {
        try(PreparedStatement statement=connection.prepareStatement(PERMISSIONS)) {
            statement.setQueryTimeout(plan.timeoutSeconds());statement.setString(1,plan.schema());statement.setString(2,plan.schema());statement.setString(3,plan.table());
            try(ResultSet rs=statement.executeQuery()) {
                if(!rs.next())throw new ReadFailure("SOURCE_UNAVAILABLE","来源数据库暂不可用；不能判断对象是否存在","source");
                if(!rs.getBoolean("schema_usable"))throw new ReadFailure("SOURCE_UNAVAILABLE","来源 schema 不可访问；不能判断对象是否存在","source");
                if(!rs.getBoolean("relation_exists"))throw new ReadFailure("BINDING_SCHEMA_DRIFT","绑定来源表不存在；请返回 M5 重新核验映射","sourceTable");
                if(!rs.getBoolean("can_select"))throw new ReadFailure("SOURCE_UNAVAILABLE","来源数据库拒绝了只读查询；不能判断对象是否存在","source");
            }
        }
    }
    private static long estimatedValueBytes(Object value) {
        if(value==null)return 1;if(value instanceof byte[] bytes)return bytes.length;
        String text;
        if(value instanceof BigDecimal d)text=d.toPlainString();
        else if(value instanceof Timestamp ts)text=ts.toInstant().toString();
        else if(value instanceof OffsetDateTime dt)text=dt.toInstant().toString();
        else if(value instanceof Instant instant)text=instant.toString();else text=String.valueOf(value);
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
