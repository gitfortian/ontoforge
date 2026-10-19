package io.ontoforge.boot.controller;

import io.ontoforge.business.registry.RuntimeTypeResolver;
import io.ontoforge.platform.identity.B0AuthorizationService;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only M4 Native ObjectType catalog; every request rechecks the existing package ACL. */
@RestController
@RequestMapping("/api/model/type-sources")
public class RuntimeTypeController {
    private final RuntimeTypeResolver resolver;
    private final B0AuthorizationService authorization;

    public RuntimeTypeController(RuntimeTypeResolver resolver, B0AuthorizationService authorization) {
        this.resolver = resolver;
        this.authorization = authorization;
    }

    @GetMapping("/drafts/{draftId}/types")
    public RuntimeTypeResolver.TypeCatalog listDraftTypes(
            @PathVariable UUID draftId,
            @RequestParam("revision") long revision,
            Authentication authentication) {
        authorization.requireDraft(authentication, draftId, B0AuthorizationService.Operation.READ_DRAFT);
        return resolver.listDraftTypes(draftId, revision);
    }

    @GetMapping("/drafts/{draftId}/types/{objectTypeId}")
    public RuntimeTypeResolver.TypeDescriptor getDraftType(
            @PathVariable UUID draftId,
            @PathVariable String objectTypeId,
            @RequestParam("revision") long revision,
            Authentication authentication) {
        authorization.requireDraft(authentication, draftId, B0AuthorizationService.Operation.READ_DRAFT);
        return resolver.getDraftType(draftId, revision, objectTypeId);
    }

    @GetMapping("/snapshots/{snapshotId}/types")
    public RuntimeTypeResolver.TypeCatalog listSnapshotTypes(
            @PathVariable UUID snapshotId,
            Authentication authentication) {
        authorization.requireSnapshot(authentication, snapshotId, B0AuthorizationService.Operation.READ_SNAPSHOT);
        return resolver.listSnapshotTypes(snapshotId);
    }

    @GetMapping("/snapshots/{snapshotId}/types/{objectTypeId}")
    public RuntimeTypeResolver.TypeDescriptor getSnapshotType(
            @PathVariable UUID snapshotId,
            @PathVariable String objectTypeId,
            Authentication authentication) {
        authorization.requireSnapshot(authentication, snapshotId, B0AuthorizationService.Operation.READ_SNAPSHOT);
        return resolver.getSnapshotType(snapshotId, objectTypeId);
    }
}
