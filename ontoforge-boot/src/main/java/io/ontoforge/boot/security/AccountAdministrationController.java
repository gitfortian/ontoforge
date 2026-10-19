package io.ontoforge.boot.security;

import io.ontoforge.platform.identity.LocalAccountService;
import io.ontoforge.platform.identity.AccountChangeException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Yak user management adapted to explicit administrator checks, soft disable and existing PG identity. */
@RestController
@Profile("!mvp-local")
@ConditionalOnProperty(name="ontoforge.security.authentication-mode",havingValue="local-session",matchIfMissing=true)
@RequestMapping("/api/platform/accounts")
public class AccountAdministrationController {
    private final LocalAccountService accounts;
    public AccountAdministrationController(LocalAccountService accounts) {this.accounts=accounts;}
    public record Create(String username,String displayName,String password,Boolean systemAdmin) {
        @Override public String toString(){return "CreateAccount[redacted]";}
    }
    public record Update(Long expectedVersion,String displayName,Boolean enabled,Boolean systemAdmin) {}
    public record Reset(Long expectedVersion,String password) {
        @Override public String toString(){return "ResetPassword[redacted]";}
    }
    @GetMapping public LocalAccountService.AccountPage list(Authentication actor,@RequestParam(defaultValue="") String q,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {
        return accounts.accounts(actor,q,page,pageSize);
    }
    @PostMapping public ResponseEntity<LocalAccountService.AccountView> create(Authentication actor,@RequestBody Create body) {
        return ResponseEntity.status(201).body(accounts.create(actor,body.username(),body.displayName(),body.password(),body.systemAdmin()));
    }
    @PutMapping("/{id}") public LocalAccountService.AccountView update(Authentication actor,@PathVariable UUID id,@RequestBody Update body) {
        return accounts.update(actor,id,body.expectedVersion(),body.displayName(),body.enabled(),body.systemAdmin());
    }
    @PostMapping("/{id}/password") public ResponseEntity<Void> reset(Authentication actor,@PathVariable UUID id,@RequestBody Reset body) {
        accounts.resetPassword(actor,id,body.expectedVersion(),body.password());return ResponseEntity.noContent().build();
    }
    @ExceptionHandler(AccountChangeException.class) void conflict(AccountChangeException failure,HttpServletResponse response) throws IOException {
        AuthResponses.error(response,failure.code().equals("ACCOUNT_NOT_FOUND")?404:409,failure.code(),failure.getMessage());
    }
    @ExceptionHandler(IllegalArgumentException.class) void invalid(IllegalArgumentException failure,HttpServletResponse response) throws IOException {
        AuthResponses.error(response,400,"INVALID_ACCOUNT_REQUEST",failure.getMessage());
    }
}
