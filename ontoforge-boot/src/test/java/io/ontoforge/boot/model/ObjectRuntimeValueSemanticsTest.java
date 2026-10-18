package io.ontoforge.boot.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ObjectRuntimeValueSemanticsTest {
    private static RuntimeTypeResolver.PropertyDescriptor property(String id, String type, boolean required) {
        return new RuntimeTypeResolver.PropertyDescriptor(id, id, id, type, required, true, "INTERNAL", null);
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
