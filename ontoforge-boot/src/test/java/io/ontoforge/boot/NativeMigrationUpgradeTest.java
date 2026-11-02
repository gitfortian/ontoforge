package io.ontoforge.boot;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** Exercises V2 -> V8 local accounts -> V9 workspace -> V10 FK -> V11 Bridge LinkBinding on a new owned test schema. */
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
                Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).target("8").load().migrate();
                UUID principal=UUID.randomUUID(),account=UUID.randomUUID(),workspace=UUID.randomUUID();
                sql.execute("INSERT INTO b0_principal(principal_id,issuer,subject,display_name) VALUES ('"+principal+"','urn:ontoforge:local','"+account+"','Migration Owner')");
                sql.execute("INSERT INTO platform_local_account(account_id,principal_id,username,password_hash,credential_version,version) VALUES ('"+account+"','"+principal+"','migration.owner','migration-fixture-hash',4,7)");
                sql.execute("INSERT INTO b0_workspace(workspace_id,workspace_key,display_name) VALUES ('"+workspace+"','migration-space','Preserved Space')");
                sql.execute("INSERT INTO b0_workspace_membership(workspace_id,principal_id,role) VALUES ('"+workspace+"','"+principal+"','OWNER')");
                sql.execute("INSERT INTO b0_package_assignment(workspace_id,package_id) VALUES ('"+workspace+"','manufacturing.assigned')");
                var current=Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).load();
                current.migrate();
                try(var row=sql.executeQuery("SELECT w.workspace_id,w.version,w.display_name,m.principal_id,m.role,a.account_id,a.password_hash,a.credential_version,a.version,p.package_id FROM b0_workspace w JOIN b0_workspace_membership m USING(workspace_id) JOIN platform_local_account a USING(principal_id) JOIN b0_package_assignment p USING(workspace_id)")) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getObject(1,UUID.class)).isEqualTo(workspace);
                    assertThat(row.getLong(2)).isEqualTo(1);
                    assertThat(row.getString(3)).isEqualTo("Preserved Space");
                    assertThat(row.getObject(4,UUID.class)).isEqualTo(principal);
                    assertThat(row.getString(5)).isEqualTo("OWNER");
                    assertThat(row.getObject(6,UUID.class)).isEqualTo(account);
                    assertThat(row.getString(7)).isEqualTo("migration-fixture-hash");
                    assertThat(row.getLong(8)).isEqualTo(4);assertThat(row.getLong(9)).isEqualTo(7);
                    assertThat(row.getString(10)).isEqualTo("manufacturing.assigned");
                    assertThat(row.next()).isFalse();
                }
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
                    row.next();assertThat(row.getInt(1)).isEqualTo(11);
                }
                // V11 must extend V10 LinkBinding without rewriting V10 or breaking
                // the original schema history. Existing FK-only rows get a safe
                // discriminator default; Bridge-only columns are optional for them.
                try(var row=sql.executeQuery("""
                        SELECT version, description, success FROM flyway_schema_history
                        WHERE version IN ('10','11') ORDER BY installed_rank
                        """)) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString(1)).isEqualTo("10");
                    assertThat(row.getBoolean(3)).isTrue();
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString(1)).isEqualTo("11");
                    assertThat(row.getString(2)).containsIgnoringCase("bridge");
                    assertThat(row.getBoolean(3)).isTrue();
                    assertThat(row.next()).isFalse();
                }
                try(var row=sql.executeQuery("""
                        SELECT column_name,is_nullable,column_default
                        FROM information_schema.columns
                        WHERE table_schema=current_schema()
                          AND table_name='runtime_link_binding_revision'
                          AND column_name IN ('mapping_mode','fk_owner','fk_source_column',
                            'referenced_identity_column','bridge_schema','bridge_source_id')
                        ORDER BY column_name
                        """)) {
                    int columns=0;
                    while(row.next()) {
                        columns++;
                        String name=row.getString(1);
                        if ("mapping_mode".equals(name)) {
                            assertThat(row.getString(2)).isEqualTo("NO");
                            assertThat(row.getString(3)).contains("FOREIGN_KEY");
                        } else if (name.startsWith("fk_") || "referenced_identity_column".equals(name)) {
                            assertThat(row.getString(2)).isEqualTo("YES");
                        }
                    }
                    assertThat(columns).isEqualTo(6);
                }
                try(var row=sql.executeQuery("""
                        SELECT count(*) FROM pg_constraint
                        WHERE conname='ck_link_revision_mapping_exclusive'
                          AND conrelid='runtime_link_binding_revision'::regclass
                        """)) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getInt(1)).isEqualTo(1);
                }
                try(var row=sql.executeQuery("SELECT count(*) FROM mvp_source_batches")) {
                    row.next();assertThat(row.getInt(1)).isEqualTo(2);
                }
                // V6/V7/V8 may only add new fixtures and versioned runtime metadata; old
                // installation, V2 user draft, V4 facts, and V5 grants remain intact.
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
