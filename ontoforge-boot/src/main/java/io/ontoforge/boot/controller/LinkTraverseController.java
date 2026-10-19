package io.ontoforge.boot.controller;

import com.fasterxml.jackson.databind.JsonNode;
import io.ontoforge.business.connection.LinkBindingService;
import io.ontoforge.business.query.LinkTraverseQueryService;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** New v2 routes; local-query-v1 query/pages stay unchanged in ObjectSetQueryController. */
@RestController
@RequestMapping("/api/runtime/object-sets/traverse")
public class LinkTraverseController {
    private final LinkTraverseQueryService service;
    public LinkTraverseController(LinkTraverseQueryService service){this.service=service;}
    @PostMapping
    public LinkTraverseQueryService.Result traverse(@RequestBody JsonNode body, Authentication auth){
        return service.execute(body,auth);
    }
    @PostMapping("/pages")
    public LinkTraverseQueryService.Result pages(@RequestBody JsonNode body,Authentication auth){
        return service.nextPage(body,auth);
    }
}
@RestControllerAdvice(assignableTypes=LinkTraverseController.class)
class LinkTraverseErrorAdvice {
    record FailureResult(String status,String executionId,String code,String message,String fieldPath){}
    @ExceptionHandler(LinkTraverseQueryService.Failure.class)
    ResponseEntity<FailureResult> error(LinkTraverseQueryService.Failure f){
        return ResponseEntity.status(f.http).body(new FailureResult(
                "FAILED",f.executionId,f.code,f.getMessage(),f.field));
    }
    @ExceptionHandler(LinkBindingService.Failure.class)
    ResponseEntity<FailureResult> link(LinkBindingService.Failure f){
        return ResponseEntity.status(f.http).body(new FailureResult(
                "FAILED",UUID.randomUUID().toString(),f.code,f.getMessage(),f.fieldPath));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<FailureResult> malformed(Exception ignored){
        return ResponseEntity.badRequest().body(new FailureResult(
                "FAILED",UUID.randomUUID().toString(),"MALFORMED_REQUEST","请求格式不合法",null));
    }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<FailureResult> source(DataAccessException ignored){
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new FailureResult(
                "FAILED",UUID.randomUUID().toString(),"SOURCE_UNAVAILABLE","来源不可用；本次未返回业务事实",null));
    }
}
