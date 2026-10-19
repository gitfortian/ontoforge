package io.ontoforge.boot.controller;

import io.ontoforge.business.connection.LinkBindingService;
import io.ontoforge.business.connection.LinkBindingService.Failure;
import io.ontoforge.business.model.NativeDraftService.DraftFailure;
import java.util.List;
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

/** M8.1 typed and versioned REST; legacy M5/M7 routes are unchanged. */
@RestController
@RequestMapping("/api/runtime/link-bindings")
public class LinkBindingController {
    private final LinkBindingService service;
    public LinkBindingController(LinkBindingService service) {this.service=service;}

    @PostMapping("/validate")
    public LinkBindingService.ValidationReport validate(
            @RequestBody LinkBindingService.Request request, Authentication actor) {
        return service.validate(request,actor);
    }
    @PostMapping
    public ResponseEntity<LinkBindingService.Detail> create(
            @RequestBody LinkBindingService.Request request,Authentication actor) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request,actor));
    }
    @PutMapping("/{linkBindingId}")
    public LinkBindingService.Detail update(@PathVariable UUID linkBindingId,
            @RequestBody LinkBindingService.Request request,Authentication actor) {
        return service.update(linkBindingId,request,actor);
    }
    @GetMapping
    public List<LinkBindingService.Detail> list(@RequestParam String packageId,Authentication actor) {
        return service.list(packageId,actor);
    }
    @GetMapping("/{linkBindingId}")
    public LinkBindingService.Detail get(@PathVariable UUID linkBindingId,
            @RequestParam(required=false) Long revision,Authentication actor) {
        return service.get(linkBindingId,revision,actor);
    }
    @GetMapping("/{linkBindingId}/revisions")
    public List<LinkBindingService.Detail> revisions(@PathVariable UUID linkBindingId,Authentication actor) {
        return service.revisions(linkBindingId,actor);
    }
}

@RestControllerAdvice(assignableTypes=LinkBindingController.class)
class LinkBindingErrorAdvice {
    record ErrorBody(String code,String message,String fieldPath,String executionId) {}
    @ExceptionHandler(Failure.class)
    ResponseEntity<ErrorBody> failure(Failure e){
        return ResponseEntity.status(e.http).body(new ErrorBody(e.code,e.getMessage(),
                e.fieldPath,UUID.randomUUID().toString()));
    }
    @ExceptionHandler(DraftFailure.class)
    ResponseEntity<ErrorBody> draft(DraftFailure e){
        return ResponseEntity.status(e.http).body(new ErrorBody(e.code,e.getMessage(),
                null,UUID.randomUUID().toString()));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class,IllegalArgumentException.class})
    ResponseEntity<ErrorBody> bad(Exception ignored){
        return ResponseEntity.badRequest().body(new ErrorBody("MALFORMED_REQUEST",
                "输入格式不合法",null,UUID.randomUUID().toString()));
    }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ErrorBody> persistence(DataAccessException ignored){
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                new ErrorBody("PERSISTENCE_UNAVAILABLE",
                        "LinkBinding 写入或读取未能完成，请使用幂等 UUID 查询原结果，不要盲目重试",
                        null,UUID.randomUUID().toString()));
    }
}
