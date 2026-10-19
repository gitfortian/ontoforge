package io.ontoforge.business.object;

import io.ontoforge.business.registry.RuntimeTypeResolver;
import io.ontoforge.business.connection.DataBindingService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ObjectRuntimeValueSemanticsTest {
    private static RuntimeTypeResolver.PropertyDescriptor property(String id, String type, boolean required) {
        return new RuntimeTypeResolver.PropertyDescriptor(id, id, id, type, required, true, "INTERNAL", null);
    }

    @Test
    void recognizesPostgresStatementTimeoutState() {
        assertThat(ObjectRuntimeService.isTimeout(
                new SQLException("canceling statement due to statement timeout", "57014"))).isTrue();
        assertThat(ObjectRuntimeService.isTimeout(
                new SQLException("permission denied", "42501"))).isFalse();
    }

    @Test
    void foldsOnlyIdenticalDuplicateRowsForRequestedProperties() {
        var key = property("supplier-code", "STRING", true);
        var name = property("supplier-name", "STRING", true);
        var selected = List.of(key, name);
        var mappings = Map.of(
                key.id(), new DataBindingService.FieldMapping(key.id(), "source_key", "AUTHORITATIVE"),
                name.id(), new DataBindingService.FieldMapping(name.id(), "name", "AUTHORITATIVE"));
        var rows = List.of(
                Map.<String, Object>of("source_key", "SUP-1", "name", "Forge Works"),
                Map.<String, Object>of("source_key", "SUP-1", "name", "Forge Works"));
        assertThatCode(() -> ObjectRuntimeService.validateDuplicateRows(rows, selected, mappings, key.id(), "exec-1"))
                .doesNotThrowAnyException();
    }
 
    @Test
    void checksDuplicateConflictsOnlyWithinRequestedPropertyScope() {
        var key = property("supplier-code", "STRING", true);
        var name = property("supplier-name", "STRING", true);
        var rating = property("supplier-rating", "DECIMAL", false);
        var mappings = Map.of(
                key.id(), new DataBindingService.FieldMapping(key.id(), "source_key", "AUTHORITATIVE"),
                name.id(), new DataBindingService.FieldMapping(name.id(), "name", "AUTHORITATIVE"),
                rating.id(), new DataBindingService.FieldMapping(rating.id(), "rating", "AUTHORITATIVE"));
        var rows = List.of(
                Map.<String, Object>of("source_key", "SUP-1", "name", "Forge Works", "rating", new java.math.BigDecimal("4.75")),
                Map.<String, Object>of("source_key", "SUP-1", "name", "Forge Works", "rating", new java.math.BigDecimal("4.10")));

        // OFIP-0008 defines conflict evaluation over the requested property scope.
        // An unrequested rating conflict must not fail a key+name read, but it must fail
        // as soon as rating is explicitly requested.
        assertThatCode(() -> ObjectRuntimeService.validateDuplicateRows(
                rows, List.of(key, name), mappings, key.id(), "exec-4"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ObjectRuntimeService.validateDuplicateRows(
                rows, List.of(key, name, rating), mappings, key.id(), "exec-5"))
                .isInstanceOf(ObjectRuntimeService.ObjectRuntimeFailure.class)
                .satisfies(error -> assertThat(((ObjectRuntimeService.ObjectRuntimeFailure) error).code())
                        .isEqualTo("PROPERTY_VALUE_CONFLICT"));
    }

    @Test
    void rejectsIdentityAndRequestedPropertyConflictsInsteadOfChoosingFirstRow() {
        var key = property("supplier-code", "STRING", true);
        var name = property("supplier-name", "STRING", true);
        var mappings = Map.of(
                key.id(), new DataBindingService.FieldMapping(key.id(), "key_value", "AUTHORITATIVE"),
                name.id(), new DataBindingService.FieldMapping(name.id(), "name", "AUTHORITATIVE"));
        var identityRows = List.of(
                Map.<String, Object>of("key_value", "SUP-1", "name", "Forge Works"),
                Map.<String, Object>of("key_value", "SUP-2", "name", "Forge Works"));
        assertThatThrownBy(() -> ObjectRuntimeService.validateDuplicateRows(
                identityRows, List.of(key, name), mappings, key.id(), "exec-2"))
                .isInstanceOf(ObjectRuntimeService.ObjectRuntimeFailure.class)
                .satisfies(error -> assertThat(((ObjectRuntimeService.ObjectRuntimeFailure) error).code())
                        .isEqualTo("OBJECT_IDENTITY_CONFLICT"));

        var propertyRows = List.of(
                Map.<String, Object>of("key_value", "SUP-1", "name", "Forge Works"),
                Map.<String, Object>of("key_value", "SUP-1", "name", "ForgeWork Ltd"));
        assertThatThrownBy(() -> ObjectRuntimeService.validateDuplicateRows(
                propertyRows, List.of(key, name), mappings, key.id(), "exec-3"))
                .isInstanceOf(ObjectRuntimeService.ObjectRuntimeFailure.class)
                .satisfies(error -> assertThat(((ObjectRuntimeService.ObjectRuntimeFailure) error).code())
                        .isEqualTo("PROPERTY_VALUE_CONFLICT"));
    }
}
