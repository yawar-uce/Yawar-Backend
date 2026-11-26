package ec.yawar.e2e;

import static io.restassured.RestAssured.given;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * Once per test run: creates the temporary client {@code yawar-e2e} and the users in Keycloak, waits until the
 * gateway can reach ms-donors, and deletes everything at the end. Test methods receive it as a parameter.
 *
 * <p>Why a test-only client: {@code yawar-web} is a public Authorization Code + PKCE client without direct access
 * grants (a browser login cannot be scripted and a password grant must never exist on it). {@code yawar-e2e}
 * is created through the admin API with the same protocol mappers, lives only while the tests run and is deleted
 * afterwards, so the realm file never gets a password-grant client.
 */
public final class E2eEnvironment implements BeforeAllCallback, ParameterResolver {

    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(E2eEnvironment.class);

    @Override
    public void beforeAll(ExtensionContext context) {
        context.getRoot().getStore(NS).computeIfAbsent(Stack.class, k -> new Stack(), Stack.class);
    }

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext extension) {
        return parameter.getParameter().getType() == Stack.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext extension) {
        return extension.getRoot().getStore(NS).get(Stack.class, Stack.class);
    }

    /** The running stack as the tests see it: URLs and one token per test user. */
    public static final class Stack implements AutoCloseable {

        private static final SecureRandom RANDOM = new SecureRandom();

        final E2eConfig config = E2eConfig.fromEnvironment();
        private final KeycloakAdmin keycloak = new KeycloakAdmin(config);
        private final List<String> userIds = new ArrayList<>();
        private String clientId;
        private boolean closed;

        /** Token of a donor (role donor, consent accepted); the HU-01 tests use it. */
        public final String donorToken;
        /** Token of a valid, verified user whose donor role was removed (no realm role at all). */
        public final String noDonorToken;

        Stack() {
            try {
                String secret = random();
                clientId = keycloak.createClient(secret);
                this.donorToken = login(secret);
                this.noDonorToken = loginWithoutDonorRole(secret);
                awaitDonorsReachable(login(secret));
            } catch (RuntimeException | Error e) {
                close();
                throw e;
            }
        }

        public String gateway() {
            return config.gatewayUrl();
        }

        private String login(String secret) {
            String email = "e2e-donor-" + random().substring(0, 10) + "@example.test";
            String password = "E2e-" + random();
            userIds.add(keycloak.createUser(email, password));
            return keycloak.token(secret, email, password);
        }

        private String loginWithoutDonorRole(String secret) {
            String email = "e2e-nodonor-" + random().substring(0, 10) + "@example.test";
            String password = "E2e-" + random();
            String id = keycloak.createUser(email, password);
            userIds.add(id);
            keycloak.removeAllRealmRoles(id);
            return keycloak.token(secret, email, password);
        }

        /**
         * The gateway answers 401 before it knows its routes, so readiness is proven with a real call: a throwaway
         * donor calls /donors/me until ms-donors is registered in Eureka, the gateway has refreshed its load
         * balancer cache and the circuit is closed (200). The call creates that throwaway donor only.
         */
        private void awaitDonorsReachable(String warmUpToken) {
            Instant deadline = Instant.now().plus(Duration.ofSeconds(150));
            int last = -1;
            while (Instant.now().isBefore(deadline)) {
                try {
                    last = given().baseUri(config.gatewayUrl()).auth().oauth2(warmUpToken)
                            .get("/api/v1/donors/me").statusCode();
                } catch (RuntimeException e) {
                    last = -1;   // gateway not listening yet
                }
                if (last == 200) {
                    return;
                }
                try {
                    Thread.sleep(2_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for the stack", e);
                }
            }
            throw new IllegalStateException("GET " + config.gatewayUrl() + "/api/v1/donors/me never returned 200 in "
                    + "150 s (last status " + last + "): is ms-donors registered in Eureka and the realm imported?");
        }

        private static String random() {
            byte[] bytes = new byte[12];
            RANDOM.nextBytes(bytes);
            return HexFormat.of().formatHex(bytes);
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            // Best effort: a failing cleanup must not hide the test result, but never leave the client behind silently.
            try {
                userIds.forEach(keycloak::deleteUser);
                if (clientId != null) {
                    keycloak.deleteClient(clientId);
                }
            } catch (RuntimeException e) {
                System.err.println("E2E cleanup failed, delete client " + E2eConfig.CLIENT_ID + " and e2e-* users in "
                        + config.realm() + " by hand: " + e.getMessage());
            }
        }
    }
}
