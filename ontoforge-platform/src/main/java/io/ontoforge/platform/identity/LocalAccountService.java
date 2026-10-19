package io.ontoforge.platform.identity;

import io.ontoforge.dao.platform.LocalAccountRepository;
import io.ontoforge.dao.platform.LocalAccountRepository.Account;
import io.ontoforge.platform.identity.yak.LocalPasswordEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Locale;
import java.util.UUID;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.dao.DuplicateKeyException;
import java.util.List;

/** Account use cases own credential verification; no servlet or client supplied principal. */
@Service
public class LocalAccountService {
    private final LocalAccountRepository repository;
    private final LocalPasswordEncoder passwords=new LocalPasswordEncoder();
    private final String unknownHash=passwords.encode(UUID.randomUUID().toString());
    private final LoginAttempts attempts=new LoginAttempts(Clock.systemUTC());
    public LocalAccountService(LocalAccountRepository repository) { this.repository=repository; }

    public static String username(String raw) {
        String value=raw==null?"":raw.trim().toLowerCase(Locale.ROOT);
        if(!value.matches("[a-z0-9._-]{3,64}")) throw new IllegalArgumentException("用户名须为 3–64 位字母、数字、点、下划线或连字符");
        return value;
    }
    public static void requirePassword(String password) {
        if(password==null || password.isBlank() || password.codePointCount(0,password.length())<12
                || password.getBytes(StandardCharsets.UTF_8).length>72)
            throw new IllegalArgumentException("密码至少 12 个字符，且不能超过 72 个 UTF-8 字节");
    }
    @Transactional
    public void bootstrap(boolean enabled,String rawUsername,String password) {
        repository.lockAccounts();
        if(repository.count()>0) return;
        if(!enabled) throw new IllegalStateException("本地账号尚未初始化：请显式配置初始化管理员开关、用户名和外部密码");
        String username=username(rawUsername);
        requirePassword(password);
        repository.insert(UUID.randomUUID(),UUID.randomUUID(),username,username,passwords.encode(password),true);
    }
    public LocalAccountPrincipal authenticate(String rawUsername,String password,String clientIp) {
        String user;
        boolean validUsername=true;
        try { user=username(rawUsername); }
        catch(IllegalArgumentException invalid) { user="<invalid-username>";validUsername=false; }
        attempts.check(user,clientIp);
        Account account=validUsername?repository.byUsername(user):null;
        boolean validLength=password!=null && password.getBytes(StandardCharsets.UTF_8).length<=72;
        boolean matched=passwords.matches(validLength?password:"",account==null?unknownHash:account.passwordHash());
        if(!validLength || !matched || account==null || !account.enabled()) {
            attempts.failed(user,clientIp);
            throw new BadCredentialsException("账号或密码不正确");
        }
        attempts.succeeded(user);
        return principal(account);
    }
    public LocalAccountPrincipal current(UUID accountId,long loginVersion) {
        Account account=repository.byId(accountId);
        return account!=null && account.enabled() && account.credentialVersion()==loginVersion ? principal(account):null;
    }
    public record AccountView(UUID accountId,UUID principalId,String username,String displayName,
                              boolean enabled,boolean systemAdmin,long version) {}
    public record AccountPage(List<AccountView> items,long total,int page,int pageSize) {}
    private static AccountView view(Account account) {
        return new AccountView(account.accountId(),account.principalId(),account.username(),account.displayName(),
                account.enabled(),account.systemAdmin(),account.version());
    }
    private Account requireLocal(Authentication authentication) {
        if(authentication==null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof LocalAccountPrincipal principal))
            throw new AccessDeniedException("需要本地账号身份");
        Account account=repository.byId(principal.accountId());
        if(account==null || !account.enabled() || account.credentialVersion()!=principal.credentialVersion())
            throw new AccessDeniedException("账号已失效，请重新登录");
        return account;
    }
    private Account requireAdmin(Authentication authentication) {
        Account account=requireLocal(authentication);
        if(!account.systemAdmin())throw new AccessDeniedException("仅系统管理员可以管理账号");
        return account;
    }
    private static String displayName(String raw) {
        String value=raw==null?"":raw.trim();
        if(value.isEmpty() || value.length()>100)throw new IllegalArgumentException("显示名称需为 1–100 个字符");
        return value;
    }
    public AccountPage accounts(Authentication authentication,String query,int page,int pageSize) {
        requireAdmin(authentication);
        String q=query==null?"":query.trim();
        if(q.length()>100 || page<1 || page>100_000 || pageSize<1 || pageSize>100)
            throw new IllegalArgumentException("账号查询范围无效");
        return new AccountPage(repository.page(q,pageSize,(page-1)*pageSize).stream().map(LocalAccountService::view).toList(),
                repository.count(q),page,pageSize);
    }
    /** Adapted Yak UserServiceImpl create/edit/reset use cases; adds admin authorization and PG CAS. */
    @Transactional
    public AccountView create(Authentication authentication,String rawUsername,String name,String password,Boolean admin) {
        repository.lockAccounts();requireAdmin(authentication);
        String username=username(rawUsername),display=displayName(name);requirePassword(password);
        if(admin==null)throw new IllegalArgumentException("请选择账号权限");
        UUID id=UUID.randomUUID();
        try {repository.insert(id,UUID.randomUUID(),username,display,passwords.encode(password),admin);}
        catch(DuplicateKeyException duplicate){throw new AccountChangeException("USERNAME_EXISTS","账号名已存在");}
        return view(repository.byId(id));
    }
    @Transactional
    public AccountView update(Authentication authentication,UUID id,Long version,String name,Boolean enabled,Boolean admin) {
        repository.lockAccounts();Account operator=requireAdmin(authentication);
        Account target=locked(id,version);
        if(enabled==null||admin==null)throw new IllegalArgumentException("账号状态和权限不能为空");
        if(operator.accountId().equals(id) && (!enabled || !admin))
            throw new AccountChangeException("SELF_ADMIN_PROTECTED","不能停用或降级当前管理员，请由其他管理员操作");
        if(!enabled && target.enabled() && repository.wouldOrphanLocalWorkspace(target.principalId()))
            throw new AccountChangeException("LAST_WORKSPACE_OWNER","请先转交工作空间的最后一个可用 OWNER");
        if(!repository.update(target,displayName(name),enabled,admin))
            throw new AccountChangeException("ACCOUNT_VERSION_CONFLICT","账号已被其他操作更新，请重新核对");
        return view(repository.byId(id));
    }
    @Transactional
    public void resetPassword(Authentication authentication,UUID id,Long version,String password) {
        repository.lockAccounts();requireAdmin(authentication);Account target=locked(id,version);requirePassword(password);
        if(!repository.password(target,passwords.encode(password)))
            throw new AccountChangeException("ACCOUNT_VERSION_CONFLICT","账号已更新，请重新核对");
    }
    @Transactional
    public void changePassword(Authentication authentication,String oldPassword,String newPassword,String ip) {
        repository.lockAccounts();Account target=requireLocal(authentication);requirePassword(newPassword);
        authenticate(target.username(),oldPassword,ip);
        if(!repository.password(target,passwords.encode(newPassword)))
            throw new AccountChangeException("ACCOUNT_VERSION_CONFLICT","账号已更新，请重新登录后核对");
    }
    private Account locked(UUID id,Long version) {
        if(version==null||version<1)throw new IllegalArgumentException("必须提供当前账号版本");
        Account account=repository.lockById(id);
        if(account==null)throw new AccountChangeException("ACCOUNT_NOT_FOUND","账号不存在");
        if(account.version()!=version)throw new AccountChangeException("ACCOUNT_VERSION_CONFLICT","账号已更新，请重新核对");
        return account;
    }
    private static LocalAccountPrincipal principal(Account account) {
        return new LocalAccountPrincipal(account.accountId(),account.principalId(),account.credentialVersion(),
                account.username(),account.displayName(),account.systemAdmin());
    }
}
