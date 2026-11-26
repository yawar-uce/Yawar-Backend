package ec.yawar.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class KeycloakRealmRoleConverterTest {

    private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "RS256").subject("s")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        claims.forEach(builder::claim);
        return builder.build();
    }

    @Test
    void realmRoles_becomeRolePrefixedAuthorities() {
        var authorities = converter.convert(jwt(Map.of("realm_access", Map.of("roles", List.of("donor", "auditor")))));

        assertThat(authorities).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_donor", "ROLE_auditor");
    }

    @Test
    void tokenWithoutRealmAccess_hasNoAuthorities() {
        assertThat(converter.convert(jwt(Map.of("scope", "openid")))).isEmpty();
    }

    @Test
    void malformedRealmAccess_hasNoAuthorities() {
        assertThat(converter.convert(jwt(Map.of("realm_access", "donor")))).isEmpty();
        assertThat(converter.convert(jwt(Map.of("realm_access", Map.of("roles", "donor"))))).isEmpty();
    }

    @Test
    void nonStringRoles_areIgnored() {
        var authorities = converter.convert(jwt(Map.of("realm_access", Map.of("roles", List.of("donor", 7)))));

        assertThat(authorities).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_donor");
    }
}
