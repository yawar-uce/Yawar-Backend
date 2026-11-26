package ec.yawar.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class JwtDecoderConfigTest {

    private static final String ISSUER = "http://localhost:8080/realms/yawar-dev";
    private final org.springframework.security.oauth2.core.OAuth2TokenValidator<Jwt> validator = JwtDecoderConfig.validator(ISSUER, List.of("yawar-api"));

    private static Jwt jwt(String issuer, List<String> audience, Instant expires) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "RS256").subject("s")
                .issuedAt(expires.minusSeconds(300)).expiresAt(expires);
        if (issuer != null) {
            builder.issuer(issuer);
        }
        if (audience != null) {
            builder.audience(audience);
        }
        return builder.build();
    }

    @Test
    void validToken_passes() {
        assertThat(validator.validate(jwt(ISSUER, List.of("yawar-api"), Instant.now().plusSeconds(60))).hasErrors())
                .isFalse();
    }

    @Test
    void tokenExpiredWithinClockSkew_stillPasses() {
        assertThat(validator.validate(jwt(ISSUER, List.of("yawar-api"), Instant.now().minusSeconds(20))).hasErrors())
                .isFalse();
    }

    @Test
    void tokenExpiredBeyondClockSkew_fails() {
        assertThat(validator.validate(jwt(ISSUER, List.of("yawar-api"), Instant.now().minusSeconds(40))).hasErrors())
                .isTrue();
    }

    @Test
    void wrongIssuer_fails() {
        assertThat(validator.validate(jwt("http://evil/realms/x", List.of("yawar-api"), Instant.now().plusSeconds(60)))
                .hasErrors()).isTrue();
    }

    @Test
    void wrongOrMissingAudience_fails() {
        Instant exp = Instant.now().plusSeconds(60);
        assertThat(validator.validate(jwt(ISSUER, List.of("account"), exp)).hasErrors()).isTrue();
        assertThat(validator.validate(jwt(ISSUER, null, exp)).hasErrors()).isTrue();
    }

    @Test
    void oneAcceptedAudienceAmongSeveral_passes() {
        assertThat(validator.validate(jwt(ISSUER, List.of("account", "yawar-api"), Instant.now().plusSeconds(60)))
                .hasErrors()).isFalse();
    }
}
