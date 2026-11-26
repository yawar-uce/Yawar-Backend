package ec.yawar.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Real gateway on a random port. Stand-ins: WireMock for the Keycloak JWKS endpoint (keys generated here) and for
 * ms-donors (the {@code lb://ms-donors} route is kept and resolved by the simple discovery client).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("it")
public abstract class GatewayITSupport {

    protected static final String ISSUER = "http://keycloak.test/realms/yawar-it";
    protected static final String KID = "test-key";
    protected static final RSAKey KEY = generateKey(KID);
    /** A key that is never published in the JWKS (used to forge tokens). */
    protected static final RSAKey ROGUE_KEY = generateKey(KID);

    protected static final WireMockServer KEYCLOAK = start();
    protected static final WireMockServer DONORS = start();

    @Autowired
    protected WebTestClient client;

    private static WireMockServer start() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        return server;
    }

    private static RSAKey generateKey(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void gatewayProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> KEYCLOAK.baseUrl() + "/jwks");
        registry.add("spring.cloud.discovery.client.simple.instances.ms-donors[0].uri", DONORS::baseUrl);
    }

    @BeforeEach
    void resetStubs() {
        publishJwks();
        DONORS.resetAll();
        DONORS.stubFor(get(urlPathEqualTo("/api/v1/donors/me")).willReturn(okJson("{\"donor\":true}")));
    }

    protected static void publishJwks() {
        KEYCLOAK.resetAll();
        KEYCLOAK.stubFor(get(urlPathEqualTo("/jwks")).willReturn(
                aResponse().withHeader("Content-Type", "application/json")
                        .withBody(new JWKSet(KEY.toPublicJWK()).toString())));
    }

    /** A signed token of a donor with a random subject (so rate limit buckets never collide between tests). */
    protected static String donorToken() {
        return token(KEY, UUID.randomUUID().toString(), ISSUER, List.of("yawar-api"), List.of("donor"), 300);
    }

    protected static String token(RSAKey signingKey, String subject, String issuer, List<String> audience,
                                  List<String> roles, long validForSeconds) {
        try {
            Date now = new Date();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(subject).issuer(issuer).audience(audience)
                    .issueTime(new Date(now.getTime() - 3_600_000))
                    .expirationTime(new Date(now.getTime() + validForSeconds * 1000))
                    .claim("realm_access", Map.of("roles", roles))
                    .build();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected static String randomIp() {
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        return "198.51.%d.%d".formatted(random.nextInt(1, 255), random.nextInt(1, 255));
    }

    /** GET /api/v1/donors/me, with a bearer token when given. */
    protected WebTestClient.RequestHeadersSpec<?> getMe(String token) {
        // A random client address per call: anonymous requests are limited per IP and all tests share 127.0.0.1.
        WebTestClient.RequestHeadersSpec<?> spec = client.get().uri("/api/v1/donors/me")
                .header("X-Forwarded-For", randomIp());
        if (token != null) {
            spec = spec.header("Authorization", "Bearer " + token);
        }
        return spec;
    }
}
