package io.ontoforge.boot.controller;

import io.ontoforge.boot.model.ObjectRuntimeService;
import io.ontoforge.boot.model.ObjectRuntimeService.ObjectReadFailure;
import io.ontoforge.boot.model.ObjectRuntimeService.ObjectReadRequest;
import io.ontoforge.boot.model.ObjectRuntimeService.ObjectRuntimeFailure;
import io.ontoforge.boot.model.ObjectRuntimeService.QualityEnvelope;
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
    public ObjectRuntimeService.ObjectReadResult read(@RequestBody ObjectReadRequest request, Authentication authentication) {
        return service.read(request, authentication);
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
