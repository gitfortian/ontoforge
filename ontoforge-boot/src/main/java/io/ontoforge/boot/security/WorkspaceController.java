package io.ontoforge.boot.security;

import io.ontoforge.platform.identity.WorkspaceService;
import io.ontoforge.platform.identity.WorkspaceChangeException;
import io.ontoforge.core.compiler.DraftValidation;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("!mvp-local")
@RequestMapping("/api/platform/workspaces")
public class WorkspaceController {
    private final WorkspaceService workspaces;
    public WorkspaceController(WorkspaceService workspaces){this.workspaces=workspaces;}
    public record Create(String workspaceKey,String displayName) {}
    public record Rename(Long expectedVersion,String displayName) {}
    public record Member(Long expectedVersion,List<String> roles) {}
    public record Assignment(String packageId) {}
    @GetMapping public List<WorkspaceService.WorkspaceView> list(Authentication actor){return workspaces.list(actor);}
    @PostMapping public org.springframework.http.ResponseEntity<WorkspaceService.WorkspaceView> create(Authentication actor,@RequestBody Create body){
        return org.springframework.http.ResponseEntity.status(201).body(workspaces.create(actor,body.workspaceKey(),body.displayName()));
    }
    @GetMapping("/{id}") public WorkspaceService.WorkspaceView get(Authentication actor,@PathVariable UUID id){return workspaces.get(actor,id);}
    @PutMapping("/{id}") public WorkspaceService.WorkspaceView rename(Authentication actor,@PathVariable UUID id,@RequestBody Rename body){return workspaces.rename(actor,id,body.expectedVersion(),body.displayName());}
    @GetMapping("/{id}/members") public WorkspaceService.Members members(Authentication actor,@PathVariable UUID id,@RequestParam(defaultValue="") String q){return workspaces.members(actor,id,q);}
    @PutMapping("/{id}/members/{principal}") public WorkspaceService.Members member(Authentication actor,@PathVariable UUID id,@PathVariable UUID principal,@RequestBody Member body){return workspaces.member(actor,id,principal,body.expectedVersion(),body.roles());}
    @DeleteMapping("/{id}/members/{principal}") public org.springframework.http.ResponseEntity<Void> remove(Authentication actor,@PathVariable UUID id,@PathVariable UUID principal,@RequestParam Long expectedVersion){
        workspaces.remove(actor,id,principal,expectedVersion);return org.springframework.http.ResponseEntity.noContent().build();
    }
    @PostMapping("/{id}/packages") public WorkspaceService.WorkspaceView assign(Authentication actor,@PathVariable UUID id,@RequestBody Assignment body){
        if(!DraftValidation.packageIdValid(body.packageId()))throw new IllegalArgumentException("模型包 ID 必须是有效域式标识（不超过 160 字符）");
        return workspaces.assign(actor,id,body.packageId());
    }
    @ExceptionHandler(WorkspaceChangeException.class) void conflict(WorkspaceChangeException failure,HttpServletResponse response)throws IOException {
        AuthResponses.error(response,failure.code().equals("WORKSPACE_NOT_FOUND")?404:409,failure.code(),failure.getMessage());
    }
    @ExceptionHandler(IllegalArgumentException.class) void invalid(IllegalArgumentException failure,HttpServletResponse response)throws IOException {
        AuthResponses.error(response,400,"INVALID_WORKSPACE_REQUEST",failure.getMessage());
    }
}
