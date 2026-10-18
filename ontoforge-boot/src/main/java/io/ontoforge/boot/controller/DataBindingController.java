package io.ontoforge.boot.controller;

import io.ontoforge.boot.model.DataBindingService;
import io.ontoforge.boot.model.DataBindingService.BindingFailure;
import io.ontoforge.boot.model.NativeDraftService.DraftFailure;
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

@RestController
@RequestMapping("/api/runtime")
public class DataBindingController {
    private final DataBindingService service;

    public DataBindingController(DataBindingService service) {
        this.service = service;
    }

    @GetMapping("/sources/{sourceId}/tables")
    public List<DataBindingService.SourceTable> tables(@PathVariable String sourceId,
            @RequestParam String packageId, Authentication auth) {
        if (!DataBindingService.SOURCE_ID.equals(sourceId)) {
            throw new BindingFailure(403, "SOURCE_NOT_ALLOWED", "来源不在服务端允许列表中", "sourceId");
        }
        return service.listTables(packageId, auth);
    }

    @GetMapping("/bindings")
    public List<DataBindingService.BindingSummary> list(@RequestParam String packageId, Authentication auth) {
        return service.listBindings(packageId, auth);
    }

    @PostMapping("/bindings/preview")
    public DataBindingService.PreviewResult preview(@RequestBody DataBindingService.BindingRequest request,
                                                     Authentication auth) {
        return service.preview(request, auth);
    }

    @PostMapping("/bindings/validate")
    public DataBindingService.ValidationResult validate(@RequestBody DataBindingService.BindingRequest request,
                                                         Authentication auth) {
        return service.validate(request, auth);
    }

    @PostMapping("/bindings")
    public ResponseEntity<DataBindingService.BindingDetail> create(
            @RequestBody DataBindingService.BindingRequest request, Authentication auth) {
        DataBindingService.BindingDetail detail = service.create(request, auth);
        return ResponseEntity.status(HttpStatus.CREATED).body(detail);
    }

    @PutMapping("/bindings/{bindingId}")
    public DataBindingService.BindingDetail update(@PathVariable UUID bindingId,
            @RequestBody DataBindingService.BindingRequest request, Authentication auth) {
        return service.update(bindingId, request, auth);
    }

    @GetMapping("/bindings/{bindingId}/revisions")
    public List<DataBindingService.RevisionSummary> revisions(@PathVariable UUID bindingId, Authentication auth) {
        return service.listRevisions(bindingId, auth);
    }
    @GetMapping("/bindings/{bindingId}")
    public DataBindingService.BindingDetail get(@PathVariable UUID bindingId,
            @RequestParam(required = false) Long revision, Authentication auth) {
        return service.getBinding(bindingId, revision, auth);
    }
}

@RestControllerAdvice(assignableTypes = DataBindingController.class)
class DataBindingErrorAdvice {
    record ApiError(String code, String message, String fieldPath, String executionId) {}

    @ExceptionHandler(BindingFailure.class)
    ResponseEntity<ApiError> bindingFailure(BindingFailure error) {
        return ResponseEntity.status(error.http).body(new ApiError(
                error.code, error.getMessage(), error.fieldPath, UUID.randomUUID().toString()));
    }

    @ExceptionHandler(DraftFailure.class)
    ResponseEntity<ApiError> draftFailure(DraftFailure error) {
        return ResponseEntity.status(error.http).body(new ApiError(
                error.code, error.getMessage(), null, UUID.randomUUID().toString()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, IllegalArgumentException.class})
    ResponseEntity<ApiError> malformed(Exception ignored) {
        return ResponseEntity.badRequest().body(new ApiError(
                "MALFORMED_REQUEST", "请求格式不合法", null, UUID.randomUUID().toString()));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiError> persistence(DataAccessException ignored) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ApiError(
                "PERSISTENCE_UNAVAILABLE", "绑定存储暂不可用；请保留当前编辑并稍后重试",
                null, UUID.randomUUID().toString()));
    }
}
