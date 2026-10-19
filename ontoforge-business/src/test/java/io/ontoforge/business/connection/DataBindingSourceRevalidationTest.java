package io.ontoforge.business.connection;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.ontoforge.business.registry.RuntimeTypeResolver;
import io.ontoforge.dao.connection.DataBindingRepository;
import io.ontoforge.dao.query.ObjectSetSourceRepository;
import io.ontoforge.dao.query.PostgresSourceRepository;
import io.ontoforge.platform.identity.B0AuthorizationService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DataBindingSourceRevalidationTest {
    private final ObjectSetSourceRepository readability = mock(ObjectSetSourceRepository.class);
    private final PostgresSourceRepository metadata = mock(PostgresSourceRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final Authentication auth = mock(Authentication.class);
    private DataBindingService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void fixture() {
        ObjectProvider<JdbcTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(jdbc);
        service = new DataBindingService(mock(DataBindingRepository.class), metadata, readability, provider,
                mock(RuntimeTypeResolver.class), mock(B0AuthorizationService.class), new ObjectMapper(), "synthetic_assets");
    }

    @Test
    void revokedSelectIsSourceUnavailableBeforeMetadataCanMisclassifyItAsDrift() {
        doThrow(new ObjectSetSourceRepository.SourceFailure("SOURCE_UNAVAILABLE", "SELECT revoked", "source"))
                .when(readability).verifyReadable(eq(jdbc), any(), anyLong());
        DataBindingService.BindingFailure failure = assertThrows(DataBindingService.BindingFailure.class,
                () -> service.revalidateSource(binding(), auth, deadline()));
        assertEquals(503, failure.http);
        assertEquals("SOURCE_UNAVAILABLE", failure.code);
        verifyNoInteractions(metadata);
    }

    @Test
    void missingRelationRemainsSchemaDriftAndRlsRemainsSourceNotAllowed() {
        doThrow(new ObjectSetSourceRepository.SourceFailure("BINDING_SCHEMA_DRIFT", "relation missing", "sourceTable"))
                .when(readability).verifyReadable(eq(jdbc), any(), anyLong());
        DataBindingService.BindingFailure missing = assertThrows(DataBindingService.BindingFailure.class,
                () -> service.revalidateSource(binding(), auth, deadline()));
        assertEquals(409, missing.http);
        assertEquals("BINDING_SCHEMA_DRIFT", missing.code);
        doThrow(new ObjectSetSourceRepository.SourceFailure("SOURCE_NOT_ALLOWED", "RLS enabled", "source"))
                .when(readability).verifyReadable(eq(jdbc), any(), anyLong());
        DataBindingService.BindingFailure rls = assertThrows(DataBindingService.BindingFailure.class,
                () -> service.revalidateSource(binding(), auth, deadline()));
        assertEquals(503, rls.http);
        assertEquals("SOURCE_NOT_ALLOWED", rls.code);
        verifyNoInteractions(metadata);
    }

    @Test
    void sourceAllowlistStillPrecedesAnySourceRead() {
        DataBindingService.BindingDetail outside = new DataBindingService.BindingDetail("id", 1, "demo", "asset", "snapshot", "payload",
                "local-pg-sandbox", "public", "unselected_table", "fingerprint", "asset_id", "domain", List.of(),
                "digest", "jcs-rfc8785-v1", "VALID", null, 0, List.of(), false, null);
        DataBindingService.BindingFailure failure = assertThrows(DataBindingService.BindingFailure.class,
                () -> service.revalidateSource(outside, auth, deadline()));
        assertEquals(409, failure.http);
        verifyNoInteractions(metadata, readability);
    }

    private static DataBindingService.BindingDetail binding() {
        return new DataBindingService.BindingDetail("id", 1, "demo", "asset", "snapshot", "payload", "local-pg-sandbox", "public",
                "synthetic_assets", "fingerprint", "asset_id", "domain", List.of(), "digest", "jcs-rfc8785-v1", "VALID", null,
                0, List.of(), false, null);
    }
    private static long deadline() { return System.nanoTime() + 3_000_000_000L; }
}
