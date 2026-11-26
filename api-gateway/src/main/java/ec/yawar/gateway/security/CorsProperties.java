package ec.yawar.gateway.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Origins allowed to call the API from a browser or WebView (one list per environment, from
 * {@code CORS_ALLOWED_ORIGINS}). Patterns follow {@code CorsConfiguration#setAllowedOriginPatterns}, e.g.
 * {@code http://localhost:[*]}.
 */
@ConfigurationProperties("yawar.gateway.cors")
public record CorsProperties(List<String> allowedOriginPatterns) {

    public CorsProperties {
        allowedOriginPatterns = allowedOriginPatterns == null ? List.of() : List.copyOf(allowedOriginPatterns);
    }
}
