package io.ontoforge.boot.controller;

import com.fasterxml.jackson.databind.JsonNode;
import io.ontoforge.boot.model.NativeSnapshotService;
import io.ontoforge.boot.model.NativeDraftService.DraftFailure;
import java.net.URI;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** S0-MVP-0.3 read-only local schema snapshots; NO approve/publish/activate APIs. */
@RestController
@RequestMapping("/api/model")
public class NativeSnapshotController {
    private final NativeSnapshotService service;
    public NativeSnapshotController(NativeSnapshotService service){this.service=service;}

    @PostMapping("/drafts/{draftId}/snapshots")
    public ResponseEntity<NativeSnapshotService.Snapshot> freeze(@PathVariable UUID draftId,
                                                                  @RequestBody JsonNode body){
        strict(body,Set.of("expectedRevision","idempotencyKey"));
        JsonNode revision=body.path("expectedRevision");
        if(!revision.isIntegralNumber()||!revision.canConvertToLong()||revision.longValue()<1)
            throw new DraftFailure(400,"MALFORMED_REQUEST","expectedRevision 必须是正整数");
        UUID key=requiredUuid(body,"idempotencyKey");
        var result=service.freeze(draftId,revision.longValue(),key);
        return result.created()
             ? ResponseEntity.created(URI.create("/api/model/snapshots/"+result.snapshot().snapshotId())).body(result.snapshot())
             : ResponseEntity.ok(result.snapshot());
    }
    @GetMapping("/drafts/{draftId}/snapshots/by-key/{idempotencyKey}")
    public NativeSnapshotService.Snapshot byKey(@PathVariable UUID draftId,
                                                @PathVariable UUID idempotencyKey){
        return service.byKey(draftId,idempotencyKey);
    }
    @GetMapping("/drafts/{draftId}/snapshots/by-revision/{revision}")
    public NativeSnapshotService.Snapshot byRevision(@PathVariable UUID draftId,
                                                      @PathVariable long revision){
        return service.byRevision(draftId,revision);
    }
    @GetMapping("/packages/{packageId}/snapshots")
    public NativeSnapshotService.SnapshotPage list(@PathVariable String packageId,
                @RequestParam(defaultValue="20") int pageSize,@RequestParam(required=false) String cursor){
        return service.list(packageId,pageSize,cursor);
    }
    @GetMapping("/snapshots/{snapshotId}")
    public NativeSnapshotService.Snapshot get(@PathVariable UUID snapshotId){
        return service.get(snapshotId);
    }
    @GetMapping("/snapshot-diffs")
    public NativeSnapshotService.SnapshotDiff diff(@RequestParam("from") UUID from,
                                                   @RequestParam("to") UUID to){
        return service.diff(from,to);
    }
    private static UUID requiredUuid(JsonNode body,String key){
        JsonNode node=body.path(key);
        if(!node.isTextual())throw new DraftFailure(400,"MALFORMED_REQUEST",key+" 必须是 UUID");
        try{return UUID.fromString(node.asText());}
        catch(IllegalArgumentException e){throw new DraftFailure(400,"MALFORMED_REQUEST",key+" 必须是 UUID");}
    }
    private static void strict(JsonNode body,Set<String> allowed){
        if(body==null||!body.isObject())
            throw new DraftFailure(400,"MALFORMED_REQUEST","请求体必须是 JSON 对象");
        body.fieldNames().forEachRemaining(key->{
            if(!allowed.contains(key))throw new DraftFailure(400,"MALFORMED_REQUEST","不允许未定义的请求字段");
        });
    }
}
