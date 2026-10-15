package io.ontology.system.platform;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequestMapping("/api/workspaces")
public class WorkspaceController {
  private final JdbcTemplate jdbc;
  public WorkspaceController(JdbcTemplate jdbc) { this.jdbc=jdbc; }
  public record NewWorkspace(@NotBlank @Size(min=2,max=80) String name) {}
  public record Workspace(String id,String name,String role) {}
  public record Overview(String id,String name,String foundationStatus,String modelStatus) {}
  private UUID userId(Authentication a) {
    var list=jdbc.query("SELECT id FROM app_user WHERE username=? AND enabled=true",
      (rs,n) -> (UUID) rs.getObject("id"),a.getName());
    if(list.size()!=1)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    return list.getFirst();
  }
  @GetMapping public List<Workspace> list(Authentication a) {
    return jdbc.query("""
      SELECT w.id::text AS id,w.name,m.role FROM app_workspace w
      JOIN app_workspace_member m ON m.workspace_id=w.id
      WHERE m.user_id=? ORDER BY w.created_at,w.id
      """,(rs,n) -> new Workspace(rs.getString("id"),rs.getString("name"),rs.getString("role")),userId(a));
  }
  @PostMapping @ResponseStatus(HttpStatus.CREATED) @Transactional
  public Workspace create(@RequestBody @Valid NewWorkspace form,Authentication a) {
    String name=form.name().trim();
    if(name.length()<2)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Name too short");
    UUID owner=userId(a),id=UUID.randomUUID();
    jdbc.update("INSERT INTO app_workspace(id,name,created_by) VALUES(?,?,?)",id,name,owner);
    jdbc.update("INSERT INTO app_workspace_member(workspace_id,user_id,role) VALUES(?,?,'OWNER')",id,owner);
    return new Workspace(id.toString(),name,"OWNER");
  }
  @GetMapping("/{id}/overview") public Overview overview(@PathVariable UUID id,Authentication a) {
    var rows=jdbc.query("""
      SELECT w.id::text AS id,w.name FROM app_workspace w
      JOIN app_workspace_member m ON m.workspace_id=w.id
      WHERE w.id=? AND m.user_id=?
      """,(rs,n)->new Overview(rs.getString("id"),rs.getString("name"),
        "FOUNDATION_READY","NOT_IMPLEMENTED"),id,userId(a));
    if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    return rows.getFirst();
  }
}
