package io.ontoforge.boot.controller;

import com.fasterxml.jackson.databind.JsonNode;
import io.ontoforge.business.query.ObjectSetQueryService;
import io.ontoforge.business.query.ObjectSetQueryService.QueryException;
import io.ontoforge.business.query.ObjectSetQueryService.QueryFailureResult;
import io.ontoforge.business.query.ObjectSetQueryService.QualityEnvelope;
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
@RequestMapping("/api/runtime/object-sets")
public class ObjectSetQueryController {
    private final ObjectSetQueryService service;

    public ObjectSetQueryController(ObjectSetQueryService service) {
        this.service = service;
    }

    @PostMapping("/query")
    public ObjectSetQueryService.QueryResult query(@RequestBody JsonNode body, Authentication authentication) {
        return service.execute(body, authentication);
    }

    @PostMapping("/pages")
    public ObjectSetQueryService.QueryResult page(@RequestBody JsonNode body, Authentication authentication) {
        return service.nextPage(body, authentication);
    }
}

@RestControllerAdvice(assignableTypes = ObjectSetQueryController.class)
class ObjectSetQueryErrorAdvice {
    @ExceptionHandler(QueryException.class)
    ResponseEntity<QueryFailureResult> queryFailure(QueryException error) {
        return ResponseEntity.status(error.http()).body(new QueryFailureResult("FAILED", error.executionId(),
                error.code(), error.getMessage(), error.fieldPath(),
                new QualityEnvelope("UNKNOWN", "UNKNOWN", "UNKNOWN", "REQUESTED_SCOPE")));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<QueryFailureResult> forbidden(AccessDeniedException ignored) {
        return failure(HttpStatus.FORBIDDEN, "FORBIDDEN", "当前主体没有该模型包或 Snapshot 的查询权限", null);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class})
    ResponseEntity<QueryFailureResult> malformed(Exception ignored) {
        return failure(HttpStatus.BAD_REQUEST, "INVALID_QUERY_AST", "请求格式不合法；未返回查询结果", null);
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<QueryFailureResult> persistence(DataAccessException ignored) {
        return failure(HttpStatus.SERVICE_UNAVAILABLE, "PERSISTENCE_UNAVAILABLE",
                "绑定或来源元数据暂不可用；未返回查询结果", null);
    }

    private ResponseEntity<QueryFailureResult> failure(HttpStatus status, String code, String message, String field) {
        return ResponseEntity.status(status).body(new QueryFailureResult("FAILED", UUID.randomUUID().toString(),
                code, message, field, new QualityEnvelope("UNKNOWN", "UNKNOWN", "UNKNOWN", "REQUESTED_SCOPE")));
    }
}
