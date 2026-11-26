package ec.yawar.gateway.security;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * Local validation of Keycloak access tokens: signature (RS256, keys cached from the JWKS endpoint), issuer,
 * audience and expiry with 30 s of clock skew. Keycloak is only needed to fetch keys, so while it is down tokens
 * signed with an already cached key keep validating (fallback of YAW-DOC-07 §9.5).
 */
@Configuration(proxyBeanMethods = false)
class JwtDecoderConfig {

    static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    @Bean
    ReactiveJwtDecoder jwtDecoder(JwtProperties properties) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000)
                .responseTimeout(Duration.ofSeconds(2));
        WebClient webClient = WebClient.builder().clientConnector(new ReactorClientHttpConnector(httpClient)).build();
        // Own key source: Spring's would refetch the JWKS for every token with an unknown kid (DoS on Keycloak).
        ThrottledJwkSource keys = new ThrottledJwkSource(properties.jwkSetUri(), webClient,
                Duration.ofSeconds(30), Duration.ofMinutes(5));
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSource(keys)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(validator(properties.issuerUri(), properties.audiences()));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> validator(String issuer, List<String> audiences) {
        return new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(CLOCK_SKEW),
                new JwtIssuerValidator(issuer),
                audienceValidator(audiences));
    }

    /** At least one of the token's audiences must be one of the accepted ones (tokens for other clients fail). */
    static OAuth2TokenValidator<Jwt> audienceValidator(List<String> accepted) {
        OAuth2Error error = new OAuth2Error("invalid_token", "The required audience is missing", null);
        return jwt -> {
            List<String> audience = jwt.getAudience();
            boolean ok = audience != null && audience.stream().anyMatch(accepted::contains);
            return ok ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(error);
        };
    }
}
