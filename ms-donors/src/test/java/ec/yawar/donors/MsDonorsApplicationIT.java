package ec.yawar.donors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ec.yawar.platform.inbox.ConsumerSupport;
import ec.yawar.platform.outbox.Outbox;
import ec.yawar.platform.outbox.PlatformScheduler;
import ec.yawar.platform.tracing.CorrelationIdFilter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;
import static org.mockito.Mockito.when;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/** The skeleton boots against real PostgreSQL 16 and RabbitMQ 4.2: schema, probes, contract and security. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("it")
@Testcontainers
class MsDonorsApplicationIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("donors_db");

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.2.9-management-alpine");

    @LocalServerPort int port;
    @MockitoBean JwtDecoder jwtDecoder;
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired Outbox outbox;
    @Autowired ConsumerSupport consumerSupport;
    @Autowired PlatformScheduler scheduler;
    @Autowired CorrelationIdFilter correlationIdFilter;

    @Test
    void flywayCreatesTheDocumentedTablesAndThePlatformTables() {
        List<String> tables = jdbc.sql("""
                SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name
                """).query(String.class).list();

        assertThat(tables).contains("donor", "consent", "last_donation", "outbox", "inbox", "flyway_schema_history");
    }

    @Test
    void consentsAndDonationHistoryAreNeverCascadeDeleted() {
        // Deletion is anonymization (RF-17): the evidence stays.
        List<String> rules = jdbc.sql("""
                SELECT confdeltype FROM pg_constraint
                WHERE conrelid IN ('consent'::regclass, 'last_donation'::regclass) AND contype = 'f'
                """).query(String.class).list();

        assertThat(rules).hasSize(2).containsOnly("r");
    }

    @Test
    void bloodTypeIsStoredAsCiphertextAndUserIdIsUnique() {
        String type = jdbc.sql("""
                SELECT data_type FROM information_schema.columns WHERE table_name = 'donor' AND column_name = 'blood_type'
                """).query(String.class).single();
        long unique = jdbc.sql("""
                SELECT count(*) FROM pg_indexes WHERE tablename = 'donor' AND indexdef LIKE '%UNIQUE%(user_id)%'
                """).query(Long.class).single();
        long activeConsent = jdbc.sql("""
                SELECT count(*) FROM pg_indexes WHERE indexname = 'uq_consent_active' AND indexdef LIKE '%WHERE (revoked_at IS NULL)%'
                """).query(Long.class).single();

        assertThat(type).isEqualTo("bytea");
        assertThat(unique).isEqualTo(1);
        assertThat(activeConsent).isEqualTo(1);
    }

    @Test
    void statementAndLockTimeoutsApplyToEveryConnection() {
        Map<String, Object> row = jdbc.sql("SELECT current_setting('statement_timeout') AS st, "
                + "current_setting('lock_timeout') AS lt").query().singleRow();

        assertThat(row).containsEntry("st", "5s").containsEntry("lt", "2s");
    }

    @Test
    void probesAreUpIncludingDatabaseAndBroker() throws Exception {
        mvc.perform(get("/health/live")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/health/ready")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void infoIsPublicForTheConfigRefreshDemo() throws Exception {
        mvc.perform(get("/info")).andExpect(status().isOk()).andExpect(jsonPath("$.yawar.environment").exists());
    }

    @Test
    void openApiContractIsPublished() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("ms-donors API"))
                .andExpect(jsonPath("$.components.securitySchemes.keycloak.scheme").value("bearer"));
    }

    @Test
    void donorEndpointsNeedAToken() throws Exception {
        mvc.perform(get("/api/v1/donors/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenWithoutTheDonorRoleIsForbidden() throws Exception {
        mvc.perform(get("/api/v1/donors/me").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_hospital"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void donorTokenPassesSecurity() throws Exception {
        // The request gets through security and reaches the controller (no consent claim: 422, not 401/403).
        mvc.perform(get("/api/v1/donors/me").with(jwt().jwt(j -> j.subject("6f1c2a5e-0000-4000-8000-000000000002"))
                        .authorities(new SimpleGrantedAuthority("ROLE_donor"))))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void overRealHttpADonorTokenGetsTheEndpointStatusNotAForbiddenErrorPage() {
        // Regression: error responses are rendered by an ERROR dispatch, which must not be denied (it was a 403).
        when(jwtDecoder.decode("donor-token")).thenReturn(Jwt.withTokenValue("donor-token").header("alg", "RS256")
                .subject("6f1c2a5e-0000-4000-8000-000000000001").issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("realm_access", Map.of("roles", List.of("donor"))).build());

        int status = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { }).build()
                .get().uri("/api/v1/donors/me").header("Authorization", "Bearer donor-token")
                .retrieve().toBodilessEntity().getStatusCode().value();

        assertThat(status).isEqualTo(422);
    }

    @Test
    void unknownPathsAreDenied() throws Exception {
        mvc.perform(get("/actuator/env").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_donor"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void responsesCarryACorrelationId() throws Exception {
        mvc.perform(get("/health/live").header("X-Correlation-Id", "req-1"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getHeader("X-Correlation-Id")).isEqualTo("req-1"));
    }

    @Test
    void platformIsWiredIn() {
        assertThat(outbox).isNotNull();
        assertThat(consumerSupport.maxAttempts()).isEqualTo(5);
        assertThat(scheduler.isRunning()).isTrue();
        assertThat(correlationIdFilter).isNotNull();
    }
}
