package io.ontoforge.boot;

import io.ontoforge.dao.connection.LinkBindingRepository;
import io.ontoforge.dao.connection.LinkBindingRepository.BridgeColumns;
import io.ontoforge.dao.connection.LinkBindingRepository.BridgeRevision;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Checks the M8.2 DAO write shape; actual V10->V11 constraints and immutable
 * histories run independently on PostgreSQL 17 in the full Flyway suite.
 * No public API or unverified Bridge FULL is exposed by this test.
 */
class LinkBindingBridgeRepositoryTest {
    @Test
    void bridgeRevisionInsertUsesExplicitNullFkAndThreeSourceEvidence() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LinkBindingRepository repo = new LinkBindingRepository(jdbc);
        UUID id = UUID.randomUUID();
        UUID snapshot = UUID.randomUUID();
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        Timestamp checkedAt = Timestamp.from(Instant.parse("2026-10-20T00:00:00Z"));
        String fp = "a".repeat(64);
        BridgeRevision revision = new BridgeRevision(
                id, 2, snapshot, "b".repeat(64), "c".repeat(64),
                source, 2, "d".repeat(64),
                target, 3, "e".repeat(64),
                fp, "f".repeat(64),
                "{\"coverage\":\"FULL\"}", "{\"matchedPairs\":3}", "VALID",
                checkedAt, "1".repeat(64),
                new BridgeColumns("source-1", "public", "maintenance_order_equipment",
                        "work_order_no", "equipment_code", "2".repeat(64)));
        repo.insertBridgeRevision(revision);
        ArgumentCaptor<String> statement = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(statement.capture(), arguments.capture());
        assertThat(statement.getValue()).contains(
                "fk_owner, fk_source_column, referenced_identity_column",
                "NULL, NULL, NULL", "'BRIDGE_TABLE'",
                "bridge_source_id", "bridge_schema_fingerprint",
                "?::jsonb");
        assertThat(arguments.getValue()).hasSize(24);
        assertThat(arguments.getValue()[0]).isEqualTo(id);
        assertThat(arguments.getValue()[2]).isEqualTo(snapshot);
        assertThat(arguments.getValue()[5]).isEqualTo(source);
        assertThat(arguments.getValue()[8]).isEqualTo(target);
        assertThat(arguments.getValue()[18]).isEqualTo("source-1");
        assertThat(arguments.getValue()[19]).isEqualTo("public");
        assertThat(arguments.getValue()[20]).isEqualTo("maintenance_order_equipment");
        assertThat(arguments.getValue()[21]).isEqualTo("work_order_no");
        assertThat(arguments.getValue()[22]).isEqualTo("equipment_code");
        assertThat(arguments.getValue()[23]).isEqualTo("2".repeat(64));
    }

    @Test
    void bridgeRevisionWithoutBridgeColumnsIsNotPersisted() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LinkBindingRepository repo = new LinkBindingRepository(jdbc);
        assertThatThrownBy(() -> repo.insertBridgeRevision(new BridgeRevision(
                UUID.randomUUID(), 1, UUID.randomUUID(), "a".repeat(64), "b".repeat(64),
                UUID.randomUUID(), 1, "c".repeat(64),
                UUID.randomUUID(), 1, "d".repeat(64),
                "e".repeat(64), "f".repeat(64),
                "{}", "{}", "VALID", Timestamp.from(Instant.now()), "1".repeat(64), null)))
                .isInstanceOf(IllegalArgumentException.class);
        org.mockito.Mockito.verifyNoInteractions(jdbc);
    }
}
