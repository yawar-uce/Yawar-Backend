package ec.yawar.donors;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/** Real PostgreSQL 16 and RabbitMQ 4.2, started once per JVM and shared by the donor ITs. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("it")
abstract class AbstractDonorsIT {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("donors_db");

    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.2.9-management-alpine");

    static {
        POSTGRES.start();
        RABBIT.start();
    }

    @MockitoBean JwtDecoder jwtDecoder;
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    /** A donor token (role donor, privacy consent accepted) for a new Keycloak subject. */
    static JwtRequestPostProcessor donorToken(UUID sub) {
        return jwt().jwt(j -> j.subject(sub.toString()).claim("privacy_consent", List.of("accepted")))
                .authorities(new SimpleGrantedAuthority("ROLE_donor"));
    }

    static JwtRequestPostProcessor donorTokenWithoutConsent(UUID sub) {
        return jwt().jwt(j -> j.subject(sub.toString())).authorities(new SimpleGrantedAuthority("ROLE_donor"));
    }

    long count(String table, String where, Object param) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE " + where).param(param).query(Long.class).single();
    }
}
