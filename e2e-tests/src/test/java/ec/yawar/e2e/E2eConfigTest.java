package ec.yawar.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The guard that keeps the admin-rights tests away from the wrong realm or a remote Keycloak. No stack needed. */
class E2eConfigTest {

    private static Map<String, String> env(String... kv) {
        Map<String, String> env = new HashMap<>(Map.of("KEYCLOAK_ADMIN_USER", "a", "KEYCLOAK_ADMIN_PASSWORD", "b"));
        for (int i = 0; i < kv.length; i += 2) {
            env.put(kv[i], kv[i + 1]);
        }
        return env;
    }

    @Test
    void defaults_areLocalDevRealm() {
        E2eConfig c = E2eConfig.from(env());
        assertThat(c.realm()).isEqualTo("yawar-dev");
        assertThat(c.keycloakUrl()).isEqualTo("http://localhost:8080");
        assertThat(c.gatewayUrl()).isEqualTo("http://localhost:8000");
    }

    @Test
    void qaRealm_isAllowed() {
        assertThat(E2eConfig.from(env("REALM", "yawar-qa")).realm()).isEqualTo("yawar-qa");
    }

    @Test
    void otherRealm_isRejected() {
        assertThatThrownBy(() -> E2eConfig.from(env("REALM", "master"))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("master");
        assertThatThrownBy(() -> E2eConfig.from(env("REALM", "yawar"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void remoteKeycloak_isRejectedByDefault() {
        assertThatThrownBy(() -> E2eConfig.from(env("KEYCLOAK_URL", "https://qa.example.com/auth")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("E2E_ALLOW_REMOTE_KEYCLOAK");
        assertThatThrownBy(() -> E2eConfig.from(env("KEYCLOAK_URL", "http://localhost.evil.test:8080")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> E2eConfig.from(env("KEYCLOAK_URL", "http://localhost@evil.test/")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void remoteKeycloak_isAllowedWithExplicitOptIn() {
        E2eConfig c = E2eConfig.from(env("KEYCLOAK_URL", "https://qa.example.com/", "E2E_ALLOW_REMOTE_KEYCLOAK", "true"));
        assertThat(c.keycloakUrl()).isEqualTo("https://qa.example.com");
    }

    @Test
    void loopbackIp_isLocal() {
        assertThat(E2eConfig.from(env("KEYCLOAK_URL", "http://127.0.0.1:8080")).realm()).isEqualTo("yawar-dev");
    }

    @Test
    void missingAdminCredentials_failFast() {
        Map<String, String> noAdmin = new HashMap<>();
        assertThatThrownBy(() -> E2eConfig.from(noAdmin)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("KEYCLOAK_ADMIN_USER");
    }
}
