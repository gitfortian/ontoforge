package io.ontoforge.boot.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ontoforge.business.connection.DataBindingService.BindingFailure;
import io.ontoforge.business.connection.LinkBindingService;
import io.ontoforge.business.connection.BridgeLinkBindingService;
import io.ontoforge.business.connection.LinkBindingService.LinkFailure;
import io.ontoforge.business.model.NativeDraftService.DraftFailure;
import io.ontoforge.dao.connection.LinkBindingRepository;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.MissingServletRequestParameterException;
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

/**
 * M8.1 service-backed LinkBinding API.
 * No arbitrary SQL, referenced PK override, raw source connection or client type digest.
 */
@RestController
@RequestMapping("/api/runtime/link-bindings")
public class LinkBindingController {
    private final LinkBindingService service;
    private final BridgeLinkBindingService bridge;
    private final ObjectMapper mapper;

    public LinkBindingController(LinkBindingService service,
            BridgeLinkBindingService bridge, ObjectMapper mapper) {
        this.service = service;
        this.bridge = bridge;
        this.mapper = mapper;
    }

    @PostMapping("/validate")
    public Object validate(@RequestBody JsonNode body, Authentication auth) {
        return isBridgeV2(body) ? bridge.validate(parseBridge(body), auth)
                : service.validate(parse(body), auth);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody JsonNode body, Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                isBridgeV2(body) ? bridge.create(parseBridge(body), auth)
                        : service.create(parse(body), auth));
    }

    @PutMapping("/{linkBindingId}")
    public Object update(@PathVariable UUID linkBindingId,
            @RequestBody JsonNode body, Authentication auth) {
        return isBridgeV2(body) ? bridge.update(linkBindingId, parseBridge(body), auth)
                : service.update(linkBindingId, parse(body), auth);
    }

    @GetMapping
    public List<LinkBindingRepository.Head> list(
            @RequestParam String packageId, Authentication auth) {
        return service.list(packageId, auth);
    }

    @GetMapping("/{linkBindingId}")
    public Object get(@PathVariable UUID linkBindingId,
            @RequestParam String packageId, @RequestParam(required = false) Long revision,
            Authentication auth) {
        try {
            return service.get(linkBindingId, packageId, revision, auth);
        } catch (LinkFailure ex) {
            if (!"LINK_BINDING_MODE_MISMATCH".equals(ex.code)) throw ex;
            return bridge.get(linkBindingId, packageId, revision, auth);
        }
    }

    private static boolean isBridgeV2(JsonNode body) {
        return body != null && "local-link-binding-v2".equals(body.path("contractVersion").asText());
    }

    private BridgeLinkBindingService.Request parseBridge(JsonNode body) {
        if (body == null || !body.isObject()
                || !fieldsOnly(body, Set.of("contractVersion", "modelSourceRef", "packageId",
                        "linkTypeId", "sourceBindingRef", "targetBindingRef", "mode",
                        "bridgeSourceRef", "expectedRevision", "idempotencyKey",
                        "expectedSchemaFingerprints", "validationScope"))
                || !fieldsOnly(body.path("modelSourceRef"), Set.of("kind", "snapshotId"))
                || !fieldsOnly(body.path("sourceBindingRef"), Set.of("bindingId", "revision", "digest"))
                || !fieldsOnly(body.path("targetBindingRef"), Set.of("bindingId", "revision", "digest"))
                || !fieldsOnly(body.path("bridgeSourceRef"),
                        Set.of("sourceId", "schema", "table", "sourceFkColumn", "targetFkColumn"))
                || !fieldsOnly(body.path("expectedSchemaFingerprints"), Set.of("source", "target", "bridge"))
                || !fieldsOnly(body.path("validationScope"), Set.of("kind", "coverage"))) {
            throw new LinkFailure(422, "MALFORMED_LINK_CONTRACT",
                    "Bridge 请求包含未批准字段或缺少必需对象", "request");
        }
        try {
            return mapper.treeToValue(body, BridgeLinkBindingService.Request.class);
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw new LinkFailure(422, "MALFORMED_LINK_CONTRACT",
                    "Bridge v2 字段值或身份引用无法解析", "request");
        }
    }

    private LinkBindingService.LinkRequest parse(JsonNode body) {
        if (body == null || !body.isObject()
                || !fieldsOnly(body, Set.of("contractVersion", "modelSourceRef", "packageId",
                        "linkTypeId", "sourceBindingRef", "targetBindingRef", "mode",
                        "fkOwner", "fkSourceColumn", "expectedRevision", "idempotencyKey",
                        "expectedSchemaFingerprints", "validationScope"))
                || !fieldsOnly(body.path("modelSourceRef"), Set.of("kind", "snapshotId"))
                || !fieldsOnly(body.path("sourceBindingRef"), Set.of("bindingId", "revision", "digest"))
                || !fieldsOnly(body.path("targetBindingRef"), Set.of("bindingId", "revision", "digest"))
                || !fieldsOnly(body.path("expectedSchemaFingerprints"), Set.of("source", "target"))
                || !fieldsOnly(body.path("validationScope"), Set.of("kind", "coverage"))) {
            throw new LinkFailure(422, "MALFORMED_LINK_CONTRACT",
                    "请求含不支持的字段或非法类型", "request");
        }
        try {
            return mapper.treeToValue(body, LinkBindingService.LinkRequest.class);
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw new LinkFailure(422, "MALFORMED_LINK_CONTRACT",
                    "字段值或身份引用无法解析", "request");
        }
    }

    private static boolean fieldsOnly(JsonNode node, Set<String> keys) {
        if (!node.isObject()) return false;
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) if (!keys.contains(names.next())) return false;
        return true;
    }
}

@RestControllerAdvice(assignableTypes = LinkBindingController.class)
class LinkBindingErrorAdvice {
    record ApiError(String code, String message, String fieldPath, String executionId) {}

    @ExceptionHandler(LinkFailure.class)
    ResponseEntity<ApiError> linkFailure(LinkFailure ex) {
        return ResponseEntity.status(ex.http).body(new ApiError(
                ex.code, ex.getMessage(), ex.fieldPath, UUID.randomUUID().toString()));
    }

    @ExceptionHandler(BindingFailure.class)
    ResponseEntity<ApiError> endpointFailure(BindingFailure ex) {
        return ResponseEntity.status(ex.http).body(new ApiError(
                ex.code, ex.getMessage(), ex.fieldPath, UUID.randomUUID().toString()));
    }

    @ExceptionHandler(DraftFailure.class)
    ResponseEntity<ApiError> draftFailure(DraftFailure ex) {
        return ResponseEntity.status(ex.http).body(new ApiError(
                ex.code, ex.getMessage(), null, UUID.randomUUID().toString()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, IllegalArgumentException.class})
    ResponseEntity<ApiError> malformed(Exception ignored) {
        return ResponseEntity.badRequest().body(new ApiError(
                "MALFORMED_REQUEST", "请求格式不合法", null, UUID.randomUUID().toString()));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiError> dataAccess(DataAccessException ignored) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ApiError(
                "PERSISTENCE_UNAVAILABLE", "关系存储暂不可用，请保留当前编辑并按幂等键恢复",
                null, UUID.randomUUID().toString()));
    }
}
