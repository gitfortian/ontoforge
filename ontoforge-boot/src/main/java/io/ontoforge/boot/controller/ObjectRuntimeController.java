package io.ontoforge.boot.controller;

import io.ontoforge.boot.model.ObjectRuntimeService;
import io.ontoforge.boot.model.ObjectRuntimeService.ObjectReadFailure;
import io.ontoforge.boot.model.ObjectRuntimeService.ObjectReadRequest;
import io.ontoforge.boot.model.ObjectRuntimeService.ObjectRuntimeFailure;
import io.ontoforge.boot.model.ObjectRuntimeService.QualityEnvelope;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@RequestMapping("/api/runtime/objects")
public class ObjectRuntimeController {
    private final ObjectRuntimeService service;

    public ObjectRuntimeController(ObjectRuntimeService service) {
        this.service = service;
    }

    @PostMapping("/read")
    public ObjectRuntimeService.ObjectReadResult read(@RequestBody JsonNode body, Authentication authentication) {
        return service.read(parseRequest(body), authentication);
    }

    private ObjectReadRequest parseRequest(JsonNode body) {
        String executionId = UUID.randomUUID().toString();
        if (body == null || !body.isObject() || !hasOnlyFields(body, Set.of("bindingId", "bindingRevision", "key", "propertyRefs"))) {
            throw new ObjectRuntimeFailure(400, "INVALID_OBJECT_REQUEST",
                    "请求包含未知字段或不是 JSON 对象；授权上下文必须由服务端决定", "request", executionId);
        }
        JsonNode bindingId = body.get("bindingId");
        JsonNode revision = body.get("bindingRevision");
        JsonNode key = body.get("key");
        if (bindingId == null || !bindingId.isTextual() || revision == null || !revision.isIntegralNumber()
                || !revision.canConvertToLong() || key == null || !key.isObject()
                || !hasOnlyFields(key, Set.of("valueType", "value"))
                || key.get("valueType") == null || !key.get("valueType").isTextual()
                || key.get("value") == null || !key.get("value").isTextual()) {
            throw new ObjectRuntimeFailure(400, "INVALID_OBJECT_REQUEST",
                    "bindingId、bindingRevision 与 key 必须符合已定义的 JSON 类型合同", "request", executionId);
        }
        java.util.List<String> propertyRefs = null;
        if (body.has("propertyRefs")) {
            JsonNode refs = body.get("propertyRefs");
            if (refs == null || !refs.isArray()) {
                throw new ObjectRuntimeFailure(400, "INVALID_OBJECT_REQUEST",
                        "propertyRefs 必须是字符串数组", "propertyRefs", executionId);
            }
            propertyRefs = new ArrayList<>();
            for (JsonNode ref : refs) {
                if (!ref.isTextual()) {
                    throw new ObjectRuntimeFailure(400, "INVALID_OBJECT_REQUEST",
                            "propertyRefs 中的每项都必须是字符串", "propertyRefs", executionId);
                }
                propertyRefs.add(ref.asText());
            }
        }
        return new ObjectReadRequest(bindingId.asText(), revision.longValue(),
                new ObjectRuntimeService.ObjectKey(key.get("valueType").asText(), key.get("value").asText()),
                propertyRefs);
    }

    private static boolean hasOnlyFields(JsonNode node, Set<String> allowed) {
        Iterator<String> fields = node.fieldNames();
        while (fields.hasNext()) if (!allowed.contains(fields.next())) return false;
        return true;
    }
}

@RestControllerAdvice(assignableTypes = ObjectRuntimeController.class)
class ObjectRuntimeErrorAdvice {
    @ExceptionHandler(ObjectRuntimeFailure.class)
    ResponseEntity<ObjectReadFailure> runtimeFailure(ObjectRuntimeFailure error) {
        return ResponseEntity.status(error.http()).body(new ObjectReadFailure("FAILED", error.executionId(),
                error.code(), error.getMessage(), error.fieldPath(),
                new QualityEnvelope("UNKNOWN", "UNKNOWN", "UNKNOWN", "REQUESTED_SCOPE")));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ObjectReadFailure> forbidden(AccessDeniedException ignored) {
        return failure(HttpStatus.FORBIDDEN, "FORBIDDEN", "当前主体没有该模型包的对象读取权限", null);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class})
    ResponseEntity<ObjectReadFailure> malformed(Exception ignored) {
        return failure(HttpStatus.BAD_REQUEST, "INVALID_OBJECT_REQUEST", "请求格式不合法", null);
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ObjectReadFailure> persistence(DataAccessException ignored) {
        return failure(HttpStatus.SERVICE_UNAVAILABLE, "PERSISTENCE_UNAVAILABLE",
                "绑定或模型元数据暂不可用；未返回对象值", null);
    }

    private ResponseEntity<ObjectReadFailure> failure(HttpStatus status, String code, String message, String field) {
        return ResponseEntity.status(status).body(new ObjectReadFailure("FAILED", UUID.randomUUID().toString(),
                code, message, field, new QualityEnvelope("UNKNOWN", "UNKNOWN", "UNKNOWN", "REQUESTED_SCOPE")));
    }
}
