package io.ontoforge.boot.controller;

import com.fasterxml.jackson.databind.JsonNode;
import io.ontoforge.boot.model.NativeDraftService;
import io.ontoforge.boot.security.B0AuthorizationService;
import org.springframework.security.core.Authentication;
import io.ontoforge.boot.model.NativeDraftService.DraftFailure;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Explicit S0 local-only API; B0 package authorization; no formal publishing or Action execution. */
@RestController
@RequestMapping("/api/model/drafts")
public class NativeDraftController {
    private final NativeDraftService service;
    private final B0AuthorizationService authorization;
    public NativeDraftController(NativeDraftService service, B0AuthorizationService authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    @GetMapping
    public NativeDraftService.DraftPage list(@RequestParam(defaultValue="20") int pageSize,
                                            @RequestParam(required=false) String cursor,
                                            Authentication authentication) {
        return authorization.listDrafts(service,pageSize,cursor,authentication);
    }
    @PostMapping
    public ResponseEntity<NativeDraftService.DraftDetail> create(@RequestBody JsonNode body, Authentication authentication) {
        strict(body,"packageId");
        String packageId=requiredText(body,"packageId");
        authorization.requirePackage(authentication, packageId, B0AuthorizationService.Operation.WRITE_DRAFT);
        var draft=service.create(packageId);
        return ResponseEntity.created(URI.create("/api/model/drafts/"+draft.draftId())).body(draft);
    }
    @GetMapping("/{draftId}")
    public NativeDraftService.DraftDetail get(@PathVariable UUID draftId, Authentication authentication) {
        authorization.requireDraft(authentication, draftId, B0AuthorizationService.Operation.READ_DRAFT);
        return service.get(draftId);
    }
    @PutMapping("/{draftId}")
    public NativeDraftService.DraftDetail save(@PathVariable UUID draftId,@RequestBody JsonNode body,
                                             Authentication authentication) {
        authorization.requireDraft(authentication, draftId, B0AuthorizationService.Operation.WRITE_DRAFT);
        strict(body,"expectedRevision","editingContent");
        long rev=requiredRevision(body);
        JsonNode content=body.path("editingContent");
        if(!content.isObject())throw new DraftFailure(400,"MALFORMED_REQUEST","editingContent 必须是对象");
        return service.save(draftId,rev,content);
    }
    @PostMapping("/{draftId}/validate")
    public NativeDraftService.ValidationReport validate(@PathVariable UUID draftId,@RequestBody JsonNode body,
                                                        Authentication authentication) {
        authorization.requireDraft(authentication, draftId, B0AuthorizationService.Operation.VALIDATE_DRAFT);
        strict(body,"expectedRevision");
        return service.validate(draftId,requiredRevision(body));
    }
    private static String requiredText(JsonNode body,String key) {
        if(body==null || !body.path(key).isTextual())
            throw new DraftFailure(400,"MALFORMED_REQUEST",key+" 必须是字符串");
        return body.path(key).asText();
    }
    private static long requiredRevision(JsonNode body) {
        JsonNode rev=body.path("expectedRevision");
        if(!rev.isIntegralNumber() || !rev.canConvertToLong() || rev.longValue()<1)
            throw new DraftFailure(400,"MALFORMED_REQUEST","expectedRevision 必须是正整数");
        return rev.longValue();
    }
    private static void strict(JsonNode body,String... keys) {
        if(body==null || !body.isObject())
            throw new DraftFailure(400,"MALFORMED_REQUEST","请求体必须是 JSON 对象");
        var allowed=java.util.Set.of(keys);
        body.fieldNames().forEachRemaining(name->{
            if(!allowed.contains(name))
                throw new DraftFailure(400,"MALFORMED_REQUEST","不允许未定义的请求字段");
        });
    }
}

/** Uniform safe errors; never expose SQL, passwords, stack traces or an artificial success. */
@RestControllerAdvice(assignableTypes={NativeDraftController.class,NativeSnapshotController.class,QualityMvpController.class})
class DraftErrorAdvice {
    record ApiError(String code,String message,List<io.ontoforge.boot.model.DraftValidation.Issue> issues) {}
    @ExceptionHandler(DraftFailure.class)
    ResponseEntity<ApiError> failure(DraftFailure e) {
        return ResponseEntity.status(e.http).body(new ApiError(e.code,e.getMessage(),e.issues));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class,IllegalArgumentException.class})
    ResponseEntity<ApiError> malformed(Exception e) {
        return ResponseEntity.badRequest().body(new ApiError("MALFORMED_REQUEST","请求格式不合法",List.of()));
    }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiError> database(DataAccessException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError("PERSISTENCE_UNAVAILABLE","PostgreSQL 暂不可用，请保留本地编辑并稍后核对",List.of()));
    }
}
