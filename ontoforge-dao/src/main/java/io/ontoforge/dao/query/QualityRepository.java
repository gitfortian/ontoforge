package io.ontoforge.dao.query;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

/** Parameterized PostgreSQL reads for the existing bounded quality sandbox. */
@Repository
public class QualityRepository {
    private final JdbcTemplate jdbc;
    public QualityRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Mapping(String batchTable, String batchId, String batchStatus, String batchWorkOrder,
                           String workTable, String workId, String workOrder,
                           String orderTable, String orderId, String orderName) {}
    public record ImpactCounts(long pathCount, long workCount, long orderCount) {}
    public record MissingCounts(long workRefs, long orderRefs) {}

    private static String qi(String identifier) {
        if (identifier == null || !identifier.matches("[a-z][a-z0-9_]{0,62}"))
            throw new IllegalArgumentException("Unsafe SQL identifier");
        return "\"" + identifier + "\"";
    }
    private static String qt(String table) {
        if (table == null || !(table.startsWith("demo_") || table.startsWith("mvp_source_")))
            throw new IllegalArgumentException("Only isolated quality source tables are allowed");
        return "public." + qi(table);
    }
    public void sourceColumns(RowCallbackHandler mapper) {
        jdbc.query("""
            SELECT c.table_name,c.column_name,c.data_type FROM information_schema.columns c
             JOIN information_schema.tables t ON t.table_name=c.table_name AND t.table_schema=c.table_schema
             WHERE c.table_schema='public' AND t.table_type='BASE TABLE'
             ORDER BY c.table_name,c.ordinal_position
            """, mapper);
    }
    public List<String> savedMappings(String packageId) {
        return jdbc.query("SELECT source_map::text FROM mvp_demo_binding WHERE package_id=?",
                (rs,i) -> rs.getString(1), packageId);
    }
    public UUID existingDraft(String packageId) {
        return jdbc.query("SELECT draft_id FROM native_package_draft WHERE package_id=?",
                rs -> rs.next() ? rs.getObject(1,UUID.class) : null, packageId);
    }
    public boolean bound(String packageId, UUID draftId, long revision) {
        return Boolean.TRUE.equals(jdbc.query("SELECT 1 FROM mvp_demo_binding WHERE package_id=? AND draft_id=? AND bound_revision=?",
                (org.springframework.jdbc.core.ResultSetExtractor<Boolean>) rs -> rs.next(), packageId, draftId, revision));
    }
    public void bind(String packageId, UUID draftId, long revision, String encodedMapping) {
        jdbc.update("""
            INSERT INTO mvp_demo_binding(package_id,draft_id,bound_revision,source_map)
              VALUES (?,?,?,?::jsonb) ON CONFLICT(package_id) DO UPDATE SET
              draft_id=EXCLUDED.draft_id,bound_revision=EXCLUDED.bound_revision,
              source_map=EXCLUDED.source_map,updated_at=clock_timestamp()
            """, packageId,draftId,revision,encodedMapping);
    }
    public int sampleCount(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM (SELECT 1 FROM " + qt(table) + " LIMIT 1000) t", Integer.class);
    }
    public <T> List<T> samples(String table,String id,String attribute,String related,RowMapper<T> mapper) {
        return jdbc.query("SELECT " + qi(id) + "::text AS row_id," + qi(attribute)
                + "::text AS row_attribute," + qi(related) + "::text AS row_related FROM " + qt(table) + " LIMIT 3", mapper);
    }
    private static String joined(Mapping m) {
        return " FROM " + qt(m.batchTable()) + " b JOIN " + qt(m.workTable()) + " w ON b."
                + qi(m.batchWorkOrder()) + "::text=w." + qi(m.workId()) + "::text JOIN "
                + qt(m.orderTable()) + " o ON w." + qi(m.workOrder()) + "::text=o." + qi(m.orderId()) + "::text";
    }
    public <T> List<T> linkedExample(Mapping m, RowMapper<T> mapper) {
        return jdbc.query("SELECT b." + qi(m.batchId()) + "::text AS batch_id,w." + qi(m.workId())
                + "::text AS work_id,o." + qi(m.orderId()) + "::text AS order_id" + joined(m) + " LIMIT 1", mapper);
    }
    public <T> List<T> trace(Mapping m,String batchId,RowMapper<T> mapper) {
        return jdbc.query("SELECT b." + qi(m.batchId()) + "::text AS batch_id,b." + qi(m.batchStatus())
                + "::text AS quality_status,w." + qi(m.workId()) + "::text AS work_order_id,o." + qi(m.orderId())
                + "::text AS order_id,o." + qi(m.orderName()) + "::text AS customer_name" + joined(m)
                + " WHERE b." + qi(m.batchId()) + "::text=? LIMIT 2",mapper,batchId);
    }
    public void limitStatementTime() { jdbc.execute("SET LOCAL statement_timeout = '3000ms'"); }
    private static String batch(Mapping m) { return "b." + qi(m.batchId()) + "::text"; }
    private static String work(Mapping m) { return "w." + qi(m.workId()) + "::text"; }
    private static String order(Mapping m) { return "o." + qi(m.orderId()) + "::text"; }
    private static String impactFrom(Mapping m) {
        return " FROM " + qt(m.batchTable()) + " b LEFT JOIN " + qt(m.workTable()) + " w ON b."
                + qi(m.batchWorkOrder()) + "::text=" + work(m) + " LEFT JOIN " + qt(m.orderTable())
                + " o ON w." + qi(m.workOrder()) + "::text=" + order(m);
    }
    private static String distinctPaths(Mapping m) {
        return "SELECT DISTINCT " + work(m) + " AS work_id," + order(m) + " AS order_id"
                + impactFrom(m) + " WHERE " + batch(m) + "=? AND " + work(m) + " IS NOT NULL AND " + order(m) + " IS NOT NULL";
    }
    public List<String> statuses(Mapping m,String batchId) {
        return jdbc.query("SELECT DISTINCT b." + qi(m.batchStatus()) + "::text AS status FROM "
                + qt(m.batchTable()) + " b WHERE " + batch(m) + "=? LIMIT 2",(rs,i)->rs.getString("status"),batchId);
    }
    public ImpactCounts impactCounts(Mapping m,String batchId) {
        return jdbc.query("SELECT count(*) AS paths,count(DISTINCT work_id) AS works,count(DISTINCT order_id) AS orders FROM ("
                + distinctPaths(m) + ") links",rs->{rs.next();return new ImpactCounts(rs.getLong("paths"),rs.getLong("works"),rs.getLong("orders"));},batchId);
    }
    public boolean multipleOrdersPerWork(Mapping m,String batchId) {
        return Boolean.TRUE.equals(jdbc.query("SELECT 1 FROM (" + distinctPaths(m)
                + ") paths GROUP BY work_id HAVING COUNT(DISTINCT order_id)>1 LIMIT 1",(org.springframework.jdbc.core.ResultSetExtractor<Boolean>) rs->rs.next(),batchId));
    }
    public MissingCounts missingReferences(Mapping m,String batchId) {
        return jdbc.query("SELECT COUNT(DISTINCT CASE WHEN " + work(m) + " IS NULL THEN b." + qi(m.batchWorkOrder())
                + "::text ELSE NULL END) AS work_missing,COUNT(DISTINCT CASE WHEN " + work(m) + " IS NOT NULL AND "
                + order(m) + " IS NULL THEN w." + qi(m.workOrder()) + "::text ELSE NULL END) AS order_missing"
                + impactFrom(m) + " WHERE " + batch(m) + "=?",rs->{rs.next();return new MissingCounts(rs.getLong("work_missing"),rs.getLong("order_missing"));},batchId);
    }
    public <T> List<T> impactRows(Mapping m,String batchId,String afterWork,String afterOrder,int limit,RowMapper<T> mapper) {
        String filter=afterWork==null?"":" AND ("+work(m)+","+order(m)+")>(?,?)";
        String sql="SELECT "+work(m)+" AS work_id,"+order(m)+" AS order_id,MIN(o."+qi(m.orderName())
                +"::text) AS customer_name,COUNT(DISTINCT o."+qi(m.orderName())+"::text) AS label_variants"
                +impactFrom(m)+" WHERE "+batch(m)+"=? AND "+work(m)+" IS NOT NULL AND "+order(m)
                +" IS NOT NULL"+filter+" GROUP BY 1,2 ORDER BY 1,2 LIMIT ?";
        Object[] args=afterWork==null?new Object[]{batchId,limit}:new Object[]{batchId,afterWork,afterOrder,limit};
        return jdbc.query(sql,mapper,args);
    }
    private static String browseSource(String table,String idColumn,String attributeColumn) {
        String id="t."+qi(idColumn)+"::text",attribute="t."+qi(attributeColumn)+"::text";
        return "SELECT "+id+" AS object_id,MIN("+attribute+") AS attribute_value,COUNT(DISTINCT "+attribute
                +") AS variants,COUNT(*) FILTER(WHERE "+attribute+" IS NULL) AS null_count,COUNT(*) AS row_count FROM "
                +qt(table)+" t WHERE "+id+" IS NOT NULL GROUP BY 1";
    }
    private static String browseOuter(String table,String id,String attribute,boolean byId,String operator,String filterValue) {
        if(!operator.equals("eq")&&!operator.equals("prefix"))throw new IllegalArgumentException("Unsupported operator");
        String scalar=byId?"object_id":"attribute_value";
        String filter=filterValue.isEmpty()?"":" WHERE "+(operator.equals("eq")?scalar+" = ?":"starts_with("+scalar+",?)");
        return " FROM ("+browseSource(table,id,attribute)+") object_rows"+filter;
    }
    public long browseCount(String table,String id,String attribute,boolean byId,String operator,String filterValue) {
        Object[] args=filterValue.isEmpty()?new Object[]{}:new Object[]{filterValue};
        Long count=jdbc.queryForObject("SELECT count(*)"+browseOuter(table,id,attribute,byId,operator,filterValue),Long.class,args);
        return count==null?0:count;
    }
    public <T> List<T> browseRows(String table,String id,String attribute,boolean byId,String operator,String filterValue,
                                 String afterId,int limit,RowMapper<T> mapper) {
        String continuation=afterId==null?"":(filterValue.isEmpty()?" WHERE ":" AND ")+"object_id > ?";
        List<Object> args=new ArrayList<>();if(!filterValue.isEmpty())args.add(filterValue);
        if(afterId!=null)args.add(afterId);args.add(limit);
        return jdbc.query("SELECT object_id,attribute_value,variants,null_count,row_count"
                +browseOuter(table,id,attribute,byId,operator,filterValue)+continuation+" ORDER BY object_id LIMIT ?",mapper,args.toArray());
    }
}
