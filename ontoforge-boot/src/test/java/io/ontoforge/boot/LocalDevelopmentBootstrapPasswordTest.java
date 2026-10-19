package io.ontoforge.boot;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.ontoforge.dao.platform.LocalAccountRepository;
import io.ontoforge.platform.identity.LocalAccountService;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LocalDevelopmentBootstrapPasswordTest {
    @Test
    void acceptsRequestedDefaultOnlyWhenDevelopmentProfileExplicitlyAllowsIt() {
        LocalAccountRepository repository = mock(LocalAccountRepository.class);
        when(repository.count()).thenReturn(0L);
        LocalAccountService accounts = new LocalAccountService(repository);

        accounts.bootstrap(true, "admin", "123456", true);

        verify(repository).insert(any(UUID.class), any(UUID.class), org.mockito.ArgumentMatchers.eq("admin"),
                org.mockito.ArgumentMatchers.eq("admin"), anyString(), anyBoolean());
    }

    @Test
    void regularBootstrapStillRejectsShortPasswords() {
        LocalAccountRepository repository = mock(LocalAccountRepository.class);
        when(repository.count()).thenReturn(0L);
        LocalAccountService accounts = new LocalAccountService(repository);

        assertThatThrownBy(() -> accounts.bootstrap(true, "admin", "123456"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).insert(any(UUID.class), any(UUID.class), anyString(), anyString(), anyString(), anyBoolean());
    }
}
