package ec.yawar.e2e;

import java.util.Map;

/** Where the stack is and how to administer Keycloak; everything comes from the environment (see the README). */
record E2eConfig(String gatewayUrl, String keycloakUrl, String realm, String adminUser, String adminPassword) {

    static final String CLIENT_ID = "yawar-e2e";

    static E2eConfig fromEnvironment() {
        return from(System.getenv());
    }

    static E2eConfig from(Map<String, String> env) {
        return new E2eConfig(
                trimSlash(env.getOrDefault("GATEWAY_URL", "http://localhost:8000")),
                trimSlash(env.getOrDefault("KEYCLOAK_URL", "http://localhost:8080")),
                env.getOrDefault("REALM", "yawar-dev"),
                required(env, "KEYCLOAK_ADMIN_USER"),
                required(env, "KEYCLOAK_ADMIN_PASSWORD"));
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
