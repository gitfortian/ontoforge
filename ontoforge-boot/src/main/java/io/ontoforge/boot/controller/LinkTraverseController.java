package io.ontoforge.boot.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ontoforge.business.connection.DataBindingService.BindingFailure;
import io.ontoforge.business.connection.LinkBindingService.LinkFailure;
import io.ontoforge.business.model.NativeDraftService.DraftFailure;
import io.ontoforge.business.query.LinkTraverseService;
import io.ontoforge.business.query.LinkTraverseService.TraverseFailure;
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

/** Independent local-query-v2 route; does NOT touch M7 local-query-v1 contracts. */
@RestController
@RequestMapping("/api/runtime/object-sets")
public class LinkTraverseController {
    private final LinkTraverseService service;
    public LinkTraverseController(LinkTraverseService service) { this.service = service; }

    @PostMapping("/traverse")
    public ObjectNode execute(@RequestBody JsonNode query, Authentication auth) {
        return service.execute(query, auth);
    }

    @PostMapping("/traverse/pages")
    public ObjectNode pages(@RequestBody JsonNode cursorBody, Authentication auth) {
        return service.nextPage(cursorBody, auth);
    }
}

@RestControllerAdvice(assignableTypes = LinkTraverseController.class)
class LinkTraverseErrorAdvice {
    // Optional fieldPath is ABSENT, never JSON null, per local-result-v2 failure schema.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Failure(String status, String executionId, String queryVersion, String code,
                   String message, String fieldPath) {}

    private static ResponseEntity<Failure> failed(int http, String code,
                                                  String message, String field) {
        return ResponseEntity.status(http).body(new Failure("FAILED", UUID.randomUUID().toString(),
                "local-query-v2", code, message, field));
    }

    @ExceptionHandler(TraverseFailure.class)
    ResponseEntity<Failure> traverse(TraverseFailure ex) {
        return failed(ex.http, ex.code, ex.getMessage(), ex.fieldPath);
    }

    @ExceptionHandler(LinkFailure.class)
    ResponseEntity<Failure> link(LinkFailure ex) {
        return failed(ex.http, ex.code, ex.getMessage(), ex.fieldPath);
    }

    @ExceptionHandler(BindingFailure.class)
    ResponseEntity<Failure> binding(BindingFailure ex) {
        return failed(ex.http, ex.code, ex.getMessage(), ex.fieldPath);
    }

    @ExceptionHandler(DraftFailure.class)
    ResponseEntity<Failure> draft(DraftFailure ex) {
        return failed(ex.http, ex.code, ex.getMessage(), null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Failure> forbidden(AccessDeniedException ex) {
        return failed(403, "FORBIDDEN", "当前主体没有查询双端模型与来源的权限", null);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class})
    ResponseEntity<Failure> malformed(Exception ex) {
        return failed(400, "INVALID_QUERY_AST", "请求格式不合法；未返回任何对象或 LinkFact", null);
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Failure> persistence(DataAccessException ex) {
        return failed(503, "PERSISTENCE_UNAVAILABLE", "查询元数据不可用，无法建立可信来源证据", null);
    }
}
