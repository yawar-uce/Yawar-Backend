package ec.yawar.e2e;

import static io.restassured.RestAssured.given;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The few Keycloak admin REST calls the tests need: a temporary client, users and their tokens. */
final class KeycloakAdmin {

    private final E2eConfig config;
    private final String adminToken;

    KeycloakAdmin(E2eConfig config) {
        this.config = config;
        Response res = given().baseUri(config.keycloakUrl()).contentType(ContentType.URLENC)
                .formParam("grant_type", "password").formParam("client_id", "admin-cli")
                .formParam("username", config.adminUser()).formParam("password", config.adminPassword())
                .post("/realms/master/protocol/openid-connect/token");
        if (res.statusCode() != 200) {
            throw new IllegalStateException("Keycloak admin login failed (" + res.statusCode() + ") at "
                    + config.keycloakUrl() + ": check KEYCLOAK_URL and the KEYCLOAK_ADMIN_* variables");
        }
        this.adminToken = res.path("access_token");
    }

    private io.restassured.specification.RequestSpecification admin() {
        return given().baseUri(config.keycloakUrl()).basePath("/admin/realms/" + config.realm())
                .auth().oauth2(adminToken).contentType(ContentType.JSON);
    }

    private static String idFromLocation(Response created, String what) {
        if (created.statusCode() != 201) {
            throw new IllegalStateException("Creating " + what + " failed: " + created.statusCode() + " "
                    + created.asString());
        }
        String location = created.header("Location");
        return location.substring(location.lastIndexOf('/') + 1);
    }

    /**
     * Creates the confidential test client with direct access grants. Its protocol mappers (API audience,
     * privacy-consent, identity-provider) and scopes are copied from {@code yawar-web}, so the tokens carry exactly
     * the claims of a real web login, while {@code yawar-web} itself keeps no password grant.
     *
     * @return the internal id of the new client
     */
    String createClient(String secret) {
        deleteClientIfExists();
        List<Map<String, Object>> webClients = admin().queryParam("clientId", "yawar-web").get("/clients")
                .then().statusCode(200).extract().jsonPath().getList("$");
        if (webClients.isEmpty()) {
            throw new IllegalStateException("Client yawar-web not found in realm " + config.realm()
                    + ": run make keycloak-import first");
        }
        Map<String, Object> web = webClients.get(0);
        List<Map<String, Object>> mappers = new ArrayList<>();
        for (Object mapper : (List<?>) web.get("protocolMappers")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> copy = new java.util.HashMap<>((Map<String, Object>) mapper);
            copy.remove("id");
            mappers.add(copy);
        }
        Map<String, Object> client = new java.util.LinkedHashMap<>();
        client.put("clientId", E2eConfig.CLIENT_ID);
        client.put("name", "Yawar E2E tests (temporary)");
        client.put("protocol", "openid-connect");
        client.put("enabled", true);
        client.put("publicClient", false);
        client.put("secret", secret);
        client.put("standardFlowEnabled", false);
        client.put("implicitFlowEnabled", false);
        client.put("directAccessGrantsEnabled", true);
        client.put("serviceAccountsEnabled", false);
        // Realm roles of the user are not narrowed by scope mappings: the test users carry exactly their own roles.
        client.put("fullScopeAllowed", true);
        client.put("defaultClientScopes", web.get("defaultClientScopes"));
        client.put("optionalClientScopes", List.of());
        client.put("protocolMappers", mappers);
        return idFromLocation(admin().body(client).post("/clients"), "client " + E2eConfig.CLIENT_ID);
    }

    void deleteClientIfExists() {
        List<Map<String, Object>> existing = admin().queryParam("clientId", E2eConfig.CLIENT_ID).get("/clients")
                .then().statusCode(200).extract().jsonPath().getList("$");
        existing.forEach(c -> deleteClient((String) c.get("id")));
    }

    void deleteClient(String id) {
        expectDeleted(admin().delete("/clients/" + id), "client " + id);
    }

    private static void expectDeleted(Response res, String what) {
        if (res.statusCode() != 204 && res.statusCode() != 404) {
            throw new IllegalStateException("Deleting " + what + " failed: " + res.statusCode());
        }
    }

    /** A donor-like account: verified email, accepted privacy consent, default roles (donor). */
    String createUser(String email, String password) {
        Map<String, Object> user = new java.util.LinkedHashMap<>();
        user.put("username", email);
        user.put("email", email);
        user.put("firstName", "E2E");
        user.put("lastName", "Prueba");
        user.put("enabled", true);
        user.put("emailVerified", true);
        user.put("attributes", Map.of("privacy_consent", List.of("accepted")));
        user.put("credentials", List.of(Map.of("type", "password", "value", password, "temporary", false)));
        return idFromLocation(admin().body(user).post("/users"), "user " + email);
    }

    /** Removes every realm role of the user (the default roles carry donor): a valid login without the donor role. */
    void removeAllRealmRoles(String userId) {
        List<Map<String, Object>> roles = admin().get("/users/" + userId + "/role-mappings/realm")
                .then().statusCode(200).extract().jsonPath().getList("$");
        admin().body(roles).delete("/users/" + userId + "/role-mappings/realm").then().statusCode(204);
    }

    void deleteUser(String id) {
        expectDeleted(admin().delete("/users/" + id), "user " + id);
    }

    /** Password grant on the test client; the token is what the gateway sees. */
    String token(String clientSecret, String username, String password) {
        Response res = given().baseUri(config.keycloakUrl()).contentType(ContentType.URLENC)
                .formParam("grant_type", "password").formParam("client_id", E2eConfig.CLIENT_ID)
                .formParam("client_secret", clientSecret).formParam("username", username)
                .formParam("password", password)
                .post("/realms/" + config.realm() + "/protocol/openid-connect/token");
        if (res.statusCode() != 200) {
            throw new IllegalStateException("Token request for " + username + " failed: " + res.statusCode() + " "
                    + res.path("error_description"));
        }
        return res.path("access_token");
    }
}
