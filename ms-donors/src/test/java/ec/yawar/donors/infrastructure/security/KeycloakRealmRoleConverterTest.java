package ec.yawar.donors.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class KeycloakRealmRoleConverterTest {

    private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "RS256").subject("s");
        claims.forEach(builder::claim);
        return builder.build();
    }

    @Test
    void mapsRealmRolesToRoleAuthorities() {
        Jwt token = jwt(Map.of("realm_access", Map.of("roles", List.of("donor", "uma_authorization"))));

        assertThat(converter.convert(token)).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_donor", "ROLE_uma_authorization");
    }

    @Test
    void tokenWithoutRealmRolesHasNoAuthorities() {
        assertThat(converter.convert(jwt(Map.of("scope", "openid")))).isEmpty();
        assertThat(converter.convert(jwt(Map.of("realm_access", Map.of("other", 1))))).isEmpty();
        assertThat(converter.convert(jwt(Map.of("realm_access", "broken")))).isEmpty();
    }

    @Test
    void ignoresNonTextRoles() {
        Jwt token = jwt(Map.of("realm_access", Map.of("roles", List.of("donor", 42))));

        assertThat(converter.convert(token)).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_donor");
    }
}
