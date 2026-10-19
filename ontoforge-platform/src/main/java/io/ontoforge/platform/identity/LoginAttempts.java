package io.ontoforge.platform.identity;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.security.authentication.LockedException;

/** Bounded, single-instance login throttling; follows Yak's account/IP strategy. */
final class LoginAttempts {
    private static final long WINDOW = Duration.ofMinutes(15).toMillis();
    private static final int MAX_KEYS = 10_000;
    private final Map<String,Attempt> attempts = new HashMap<>();
    private final Clock clock;
    private record Attempt(int failures,long expiresAt) {}
    LoginAttempts(Clock clock) { this.clock=clock; }
    synchronized void check(String user,String ip) {
        long now=clock.millis();
        attempts.entrySet().removeIf(e->e.getValue().expiresAt()<=now);
        for(String key:new String[]{"user:"+user,"ip:"+ip}) {
            Attempt entry=attempts.get(key);
            if(entry!=null && entry.failures()>=5) throw new LockedException("登录尝试过多，请稍后重试");
        }
        if(attempts.size()>MAX_KEYS-2) throw new LockedException("登录尝试过多，请稍后重试");
    }
    synchronized void failed(String user,String ip) {
        long now=clock.millis();
        for(String key:new String[]{"user:"+user,"ip:"+ip}) {
            Attempt old=attempts.get(key);
            if(old==null && attempts.size()>=MAX_KEYS) continue;
            int count=old==null?1:old.failures()+1;
            attempts.put(key,new Attempt(count,old==null||count==5?now+WINDOW:old.expiresAt()));
        }
    }
    synchronized void succeeded(String user) { attempts.remove("user:"+user); }
}
