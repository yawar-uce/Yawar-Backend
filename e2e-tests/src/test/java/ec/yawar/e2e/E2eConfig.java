package ec.yawar.e2e;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Where the stack is and how to administer Keycloak; everything comes from the environment (see the README). */
record E2eConfig(String gatewayUrl, String keycloakUrl, String realm, String adminUser, String adminPassword) {

    static final String CLIENT_ID = "yawar-e2e";

    static E2eConfig fromEnvironment() {
        return from(System.getenv());
    }

    private static final Set<String> REALMS = Set.of("yawar-dev", "yawar-qa");
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1");

    /**
     * The tests create and delete clients and users with admin rights, so they refuse anything that is not a Yawar
     * test realm, and a Keycloak that is not on this machine unless E2E_ALLOW_REMOTE_KEYCLOAK=true.
     */
    static E2eConfig from(Map<String, String> env) {
        String realm = env.getOrDefault("REALM", "yawar-dev");
        if (!REALMS.contains(realm)) {
            throw new IllegalStateException("REALM '" + realm + "' is not allowed: only " + new TreeSet<>(REALMS));
        }
        String keycloakUrl = trimSlash(env.getOrDefault("KEYCLOAK_URL", "http://localhost:8080"));
        String host = hostOf(keycloakUrl);
        if (!LOCAL_HOSTS.contains(host) && !"true".equals(env.get("E2E_ALLOW_REMOTE_KEYCLOAK"))) {
            throw new IllegalStateException("KEYCLOAK_URL host '" + host + "' is not local: set "
                    + "E2E_ALLOW_REMOTE_KEYCLOAK=true to run the tests (with admin rights) against it");
        }
        return new E2eConfig(
                trimSlash(env.getOrDefault("GATEWAY_URL", "http://localhost:8000")),
                keycloakUrl,
                realm,
                required(env, "KEYCLOAK_ADMIN_USER"),
                required(env, "KEYCLOAK_ADMIN_PASSWORD"));
    }

    private static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) {
                throw new IllegalStateException("KEYCLOAK_URL '" + url + "' has no host");
            }
            return host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("KEYCLOAK_URL '" + url + "' is not a valid URL", e);
        }
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not set: export it from deploy/local/.env (make e2e does it)");
        }
        return value;
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** Never print the admin password. */
    @Override
    public String toString() {
        return "E2eConfig[gateway=" + gatewayUrl + ", keycloak=" + keycloakUrl + ", realm=" + realm + "]";
    }
}
