package io.ontoforge.platform.identity;

import io.ontoforge.dao.platform.LocalAccountRepository;
import io.ontoforge.dao.platform.WorkspaceRepository;
import io.ontoforge.dao.platform.WorkspaceRepository.Workspace;
import io.ontoforge.dao.platform.WorkspaceRepository.Person;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Yak Ops WorkspaceServiceImpl (85338827, Apache-2.0) create/discover/member flows adapted
 * to existing V5 identities/roles, PostgreSQL workspace revisions and explicit package ownership.
 */
@Service
@Profile("!mvp-local")
public class WorkspaceService {
    private final WorkspaceRepository repository;
    private final LocalAccountRepository accounts;
    private final LocalAccountService localAccounts;
    private final B0AuthorizationService authorization;
    public WorkspaceService(WorkspaceRepository repository,LocalAccountRepository accounts,
                            LocalAccountService localAccounts,B0AuthorizationService authorization) {
        this.repository=repository;this.accounts=accounts;this.localAccounts=localAccounts;this.authorization=authorization;
    }
    private record Actor(UUID principal,String issuer,boolean session,boolean admin) {}
    public record WorkspaceView(UUID workspaceId,String workspaceKey,String displayName,long version,
                                List<String> roles,List<String> packageIds,boolean canManage) {}
    public record MemberView(UUID principalId,String displayName,String username,boolean eligible,List<String> roles) {}
    public record Members(WorkspaceView workspace,List<MemberView> members,List<MemberView> candidates) {}
    private static MemberView memberView(Person person) {
        return new MemberView(person.principalId(),person.displayName(),person.username(),person.eligible(),person.roles());
    }
    private Actor actor(Authentication authentication) {
        var trusted=TrustedActor.require(authentication);
        boolean session=authentication.getPrincipal() instanceof LocalAccountPrincipal;
        boolean admin=false;
        if(authentication.getPrincipal() instanceof LocalAccountPrincipal principal) {
            var current=localAccounts.current(principal.accountId(),principal.credentialVersion());
            if(current==null)throw new AccessDeniedException("账号已失效，请重新登录");
            admin=current.systemAdmin();
        }
        return new Actor(authorization.identity(authentication).principalId(),trusted.issuer(),session,admin);
    }
    private WorkspaceView view(Workspace workspace,Actor actor) {
        List<String> roles=repository.roles(workspace.id(),actor.principal());
        return new WorkspaceView(workspace.id(),workspace.key(),workspace.name(),workspace.version(),roles,
                repository.packages(workspace.id()),actor.admin()||roles.contains("OWNER"));
    }
    private Workspace require(UUID id,Actor actor,boolean manage,boolean lock) {
        Workspace workspace=repository.get(id,lock);
        if(workspace==null)throw new WorkspaceChangeException("WORKSPACE_NOT_FOUND","工作空间不存在");
        List<String> roles=repository.roles(id,actor.principal());
        if(!actor.admin() && (roles.isEmpty() || (manage&&!roles.contains("OWNER"))))
            throw new AccessDeniedException("当前主体无权操作该工作空间");
        return workspace;
    }
    private static String name(String raw) {
        String value=raw==null?"":raw.trim();
        if(value.isEmpty()||value.length()>100)throw new IllegalArgumentException("名称需为 1–100 个字符");
        return value;
    }
    private static void expected(Workspace workspace,Long version) {
        if(version==null||version<1)throw new IllegalArgumentException("必须提供当前工作空间版本");
        if(workspace.version()!=version)throw new WorkspaceChangeException("WORKSPACE_VERSION_CONFLICT","工作空间已更新，请刷新核对后再提交");
    }
    private void bump(Workspace workspace,String displayName) {
        if(!repository.revise(workspace.id(),workspace.version(),displayName))
            throw new WorkspaceChangeException("WORKSPACE_VERSION_CONFLICT","工作空间已更新，请刷新核对");
    }
    public List<WorkspaceView> list(Authentication authentication) {
        Actor actor=actor(authentication);
        return repository.list(actor.principal(),actor.admin()).stream().map(space->view(space,actor)).toList();
    }
    public WorkspaceView get(Authentication authentication,UUID id) {
        Actor actor=actor(authentication);return view(require(id,actor,false,false),actor);
    }
    @Transactional public WorkspaceView create(Authentication authentication,String key,String displayName) {
        accounts.lockAccounts();Actor actor=actor(authentication);
        String normalized=key==null?"":key.trim().toLowerCase(Locale.ROOT);
        if(!normalized.matches("[a-z][a-z0-9-]{2,63}"))throw new IllegalArgumentException("空间标识需为 3–64 位小写字母、数字或连字符，以字母开头");
        UUID id=UUID.randomUUID();
        try {repository.create(id,normalized,name(displayName),actor.principal());}
        catch(DuplicateKeyException conflict){throw new WorkspaceChangeException("WORKSPACE_KEY_EXISTS","空间标识已存在");}
        return view(repository.get(id,false),actor);
    }
    @Transactional public WorkspaceView rename(Authentication authentication,UUID id,Long version,String displayName) {
        accounts.lockAccounts();Actor actor=actor(authentication);Workspace workspace=require(id,actor,true,true);expected(workspace,version);
        bump(workspace,name(displayName));return view(repository.get(id,false),actor);
    }
    public Members members(Authentication authentication,UUID id,String query) {
        Actor actor=actor(authentication);Workspace workspace=require(id,actor,false,false);WorkspaceView view=view(workspace,actor);
        String q=query==null?"":query.trim();
        if(!q.isEmpty()&&!view.canManage())throw new AccessDeniedException("仅 OWNER 或系统管理员可以检索待加入的主体");
        if(q.length()>100 || (!q.isEmpty()&&q.length()<2))throw new IllegalArgumentException("主体搜索需为 2–100 个字符");
        return new Members(view,repository.members(id,actor.session(),actor.issuer()).stream().map(WorkspaceService::memberView).toList(),
                q.isEmpty()?List.of():repository.candidates(q,actor.session(),actor.issuer()).stream().map(WorkspaceService::memberView).toList());
    }
    @Transactional public Members member(Authentication authentication,UUID id,UUID principal,Long version,List<String> roles) {
        accounts.lockAccounts();Actor actor=actor(authentication);Workspace workspace=require(id,actor,true,true);expected(workspace,version);
        if(roles==null||roles.isEmpty()||roles.size()>6||new HashSet<>(roles).size()!=roles.size()
                ||roles.stream().anyMatch(role->role==null||Arrays.stream(B0AuthorizationService.PackageRole.values()).noneMatch(value->value.name().equals(role))))
            throw new IllegalArgumentException("必须选择不重复的有效角色");
        if(!repository.eligible(principal,actor.session(),actor.issuer()))
            throw new WorkspaceChangeException("INACTIVE_PRINCIPAL","请选择当前认证模式下已登记且可用的主体");
        ensureOwner(id,principal,roles,actor);
        repository.replaceRoles(id,principal,roles);bump(workspace,workspace.name());
        return members(authentication,id,"");
    }
    private void ensureOwner(UUID id,UUID principal,List<String> replacement,Actor actor) {
        if(repository.roles(id,principal).contains("OWNER")&&!replacement.contains("OWNER")
                &&repository.usableOwners(id,principal,actor.session(),actor.issuer())==0)
            throw new WorkspaceChangeException("LAST_WORKSPACE_OWNER","不能移除当前认证模式下最后一个可用 OWNER");
    }
    @Transactional public void remove(Authentication authentication,UUID id,UUID principal,Long version) {
        accounts.lockAccounts();Actor actor=actor(authentication);Workspace workspace=require(id,actor,true,true);expected(workspace,version);
        ensureOwner(id,principal,List.of(),actor);
        if(!repository.roles(id,principal).isEmpty()) {repository.replaceRoles(id,principal,List.of());bump(workspace,workspace.name());}
    }
    @Transactional public WorkspaceView assign(Authentication authentication,UUID id,String packageId) {
        accounts.lockAccounts();Actor actor=actor(authentication);Workspace workspace=require(id,actor,true,true);
        repository.lockPackage(packageId);
        UUID existing=repository.packageOwner(packageId);
        if(existing!=null) {
            if(!existing.equals(id))throw new WorkspaceChangeException("PACKAGE_ALREADY_ASSIGNED","该模型包已有其他空间归属，不能重复分配");
            return view(workspace,actor);
        }
        if(repository.existingModel(packageId)&&!actor.admin())
            throw new AccessDeniedException("历史未归属模型包必须由系统管理员显式分配");
        repository.assign(id,packageId);bump(workspace,workspace.name());
        return view(repository.get(id,false),actor);
    }
}
