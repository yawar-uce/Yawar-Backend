package ec.yawar.gateway.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Same property names (and environment variables) as the resource server of every service. {@code issuerUri} is the
 * {@code iss} the browser sees; the keys are fetched from {@code jwkSetUri}, which inside compose/Kubernetes is
 * another host name.
 */
@ConfigurationProperties("spring.security.oauth2.resourceserver.jwt")
public record JwtProperties(String issuerUri, String jwkSetUri, List<String> audiences) {

    public JwtProperties {
        audiences = audiences == null ? List.of() : List.copyOf(audiences);
    }
}
