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
    private static LocalAccountPrincipal principal(Account account) {
        return new LocalAccountPrincipal(account.accountId(),account.principalId(),account.credentialVersion(),
                account.username(),account.displayName(),account.systemAdmin());
    }
}
