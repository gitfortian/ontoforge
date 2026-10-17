package io.ontoforge.boot;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** Exercises an existing V2 installation -> V6, in a new owned test schema only. */
class NativeMigrationUpgradeTest {
    @Test void upgradePreservesExistingInstallationAndDraft() throws Exception {
        String url=System.getenv("TEST_DB_URL");
        IsolatedPostgresTestDatabase.requireIsolatedUrl(url);
        String user=System.getenv("TEST_DB_USERNAME"),password=System.getenv("TEST_DB_PASSWORD");
        String schema="upgrade_"+UUID.randomUUID().toString().replace("-","");
        try(var connection=DriverManager.getConnection(url,user,password);var sql=connection.createStatement()) {
            sql.execute("CREATE SCHEMA "+schema);
            try {
                var old=Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema)
                        .target("2").load();
                old.migrate();
                sql.execute("SET search_path TO "+schema);
                UUID draft=UUID.randomUUID();
                String editor="{\"editorSchemaVersion\":\"0.1.0\",\"objectTypes\":[],\"linkTypes\":[]}";
                try(var insert=connection.prepareStatement("INSERT INTO native_package_draft "
                        +"(draft_id,package_id,editor_schema_version,editing_document,revision) VALUES (?,?,'0.1.0',?::jsonb,7)")) {
                    insert.setObject(1,draft);insert.setString(2,"manufacturing.legacy");insert.setString(3,editor);
                    insert.executeUpdate();
                }
                String installation;
                try(var row=sql.executeQuery("SELECT installation_id::text FROM platform_installation")) {
                    row.next();installation=row.getString(1);
                }
                var current=Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).load();
                current.migrate();
                try(var row=sql.executeQuery("SELECT installation_id::text FROM platform_installation")) {
                    row.next();assertThat(row.getString(1)).isEqualTo(installation);
                }
                try(var row=sql.executeQuery("SELECT revision,editing_document::text FROM native_package_draft")) {
                    row.next();assertThat(row.getLong(1)).isEqualTo(7);
                    assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(row.getString(2)))
                            .isEqualTo(new com.fasterxml.jackson.databind.ObjectMapper().readTree(editor));
                }
                assertThat(current.migrate().migrationsExecuted).isZero();
                try(var row=sql.executeQuery("SELECT count(*) FROM flyway_schema_history WHERE success")) {
                    row.next();assertThat(row.getInt(1)).isEqualTo(6);
                }
                try(var row=sql.executeQuery("SELECT count(*) FROM mvp_source_batches")) {
                    row.next();assertThat(row.getInt(1)).isEqualTo(2);
                }
                // V6 may only ADD disposable synthetic impact test fixtures; old
                // installation, V2 user draft, V4 facts and V5 grants remain intact.
                try(var row=sql.executeQuery("SELECT count(*) FROM demo_impact_lots WHERE lot_no='LOT-900'")) {
                    row.next();assertThat(row.getInt(1)).isEqualTo(3);
                }
                try(var row=sql.executeQuery("SELECT count(*) FROM mvp_demo_binding")) {
                    row.next();assertThat(row.getInt(1)).isZero();
                }
                try(var row=sql.executeQuery("SELECT count(*) FROM b0_package_assignment WHERE package_id='manufacturing.legacy'")) {
                    row.next();assertThat(row.getInt(1)).isZero(); // V5 must not auto-claim historical packages.
                }
            } finally {
                // Only this randomly named schema created above is removed.
                sql.execute("SET search_path TO public");
                sql.execute("DROP SCHEMA "+schema+" CASCADE");
            }
        }
    }
}
