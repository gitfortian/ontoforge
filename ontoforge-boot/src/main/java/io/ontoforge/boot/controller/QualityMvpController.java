package io.ontoforge.boot.controller;

import com.fasterxml.jackson.databind.JsonNode;
import io.ontoforge.business.query.QualityMvpService;
import io.ontoforge.business.query.QualityMvpService.TableInfo;
import io.ontoforge.business.query.QualityMvpService.Mapping;
import io.ontoforge.business.query.QualityMvpService.SourcePreview;
import io.ontoforge.business.query.QualityMvpService.Status;
import io.ontoforge.business.query.QualityMvpService.Binding;
import io.ontoforge.business.query.QualityMvpService.Trace;
import io.ontoforge.business.query.QualityMvpService.TraceSet;
import io.ontoforge.business.query.QualityMvpService.ObjectSetPage;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;

/** HTTP adapter; transactions, authorization and domain decisions live in Business. */
@RestController
@RequestMapping("/api/mvp/quality")
public class QualityMvpController {
    private final QualityMvpService service;
    public QualityMvpController(QualityMvpService service) { this.service=service; }
    @GetMapping("/sources")
    public List<TableInfo> sources(Authentication auth) { return service.sources(auth); }
    @GetMapping("/binding")
    public Mapping binding(Authentication auth) { return service.binding(auth); }
    @PostMapping("/preview")
    public SourcePreview preview(@RequestBody JsonNode requested,Authentication auth) { return service.preview(requested,auth); }
    @GetMapping("/status")
    public Status status(Authentication auth) { return service.status(auth); }
    @PostMapping("/model")
    public Status prepareModel(Authentication auth) { return service.prepareModel(auth); }
    @PostMapping("/bind")
    public Binding bind(@RequestBody(required=false) JsonNode chosen,Authentication auth) { return service.bind(chosen,auth); }
    @GetMapping("/trace")
    public Trace trace(@RequestParam String batchId,Authentication auth) { return service.trace(batchId,auth); }
    @GetMapping("/trace-set")
    public TraceSet traceSet(@RequestParam String batchId,@RequestParam(defaultValue="10") int pageSize,
            @RequestParam(required=false) String cursor,Authentication auth) { return service.traceSet(batchId,pageSize,cursor,auth); }
    @GetMapping("/object-set")
    public ObjectSetPage objectSet(@RequestParam String objectTypeId,@RequestParam(defaultValue="id") String filterField,
            @RequestParam(defaultValue="prefix") String operator,@RequestParam(defaultValue="") String filterValue,
            @RequestParam(defaultValue="10") int pageSize,@RequestParam(required=false) String cursor,Authentication auth) {
        return service.objectSet(objectTypeId,filterField,operator,filterValue,pageSize,cursor,auth);
    }
}
