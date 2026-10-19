package io.ontoforge.boot;

import io.ontoforge.dao.connection.LinkBindingRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Actual Flyway V8 migration and PostgreSQL constraints against the isolated
 * ontoforge_test_* test database, never the developer's application database.
 * These are metadata admission tests, not the M8.1 HTTP/React/Traverse acceptance.
 */
@SpringBootTest
@ActiveProfiles({"mvp-local", "integration-test"})
@ContextConfiguration(initializers = IsolatedPostgresTestDatabase.class)
class LinkBindingSchemaIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired LinkBindingRepository repo;

    @Test
    void migrationProvidesAllThreeImmutableLinkMetadataTables() {
        for (String name : new String[]{
                "runtime_link_binding",
                "runtime_link_binding_revision",
                "runtime_link_binding_operation"}) {
            assertThat(jdbc.queryForObject("SELECT to_regclass('public." + name + "')::text",
                    String.class)).isEqualTo(name);
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM flyway_schema_history
                WHERE version='8' AND success=true
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void packageHeadCannotAccidentallyEscapeItsScope() {
        String packageId = "m8.schema.test." + UUID.randomUUID();
        UUID id = UUID.randomUUID();
        UUID snapshot = UUID.randomUUID();
        // Native snapshot identity is an FK: an arbitrary unverified snapshot
        // may not be inserted as a link head.
        assertThatThrownBy(() -> repo.createHead(id, packageId, snapshot, "equipment-inspections"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(repo.lockHead(id, packageId)).isNull();
        assertThat(repo.lockHead(id, "foreign.package")).isNull();
    }

    @Test
    void fullValidationConstraintAndOperationReceiptUniquenessExist() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_constraint
                 WHERE conname IN ('uq_runtime_link_binding_identity',
                                   'fk_runtime_link_binding_current_revision',
                                   'fk_link_revision_source_binding',
                                   'fk_link_revision_target_binding',
                                   'fk_link_operation_revision')
                """, Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_trigger
                 WHERE tgname IN ('trg_runtime_link_binding_revision_immutable',
                                  'trg_runtime_link_binding_operation_immutable')
                   AND NOT tgisinternal
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_constraint
                 WHERE conname = 'runtime_link_binding_operation_pkey'
                """, Integer.class)).isEqualTo(1);
    }
}
