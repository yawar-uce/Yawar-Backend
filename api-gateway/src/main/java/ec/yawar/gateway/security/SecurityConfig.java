package ec.yawar.gateway.security;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * Edge security: the JWT is validated here and the role is checked per route group; the services validate it again
 * and check resource ownership (defence in depth, T01). Everything not listed is denied.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
class SecurityConfig {

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        return http
                .authorizeExchange(auth -> auth
                        .pathMatchers("/health/live", "/health/ready", "/info").permitAll()
                        .pathMatchers("/api/v1/donors/**").hasRole("donor")
                        // Reached only by the circuit breaker's forward: (which does not pass through this
                        // chain); a client calling it directly is denied like any unknown path.
                        .pathMatchers("/fallback/**").denyAll()
                        .anyExchange().denyAll())
                .oauth2ResourceServer(rs -> rs
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(
                                new ReactiveJwtAuthenticationConverterAdapter(converter)))
                        .authenticationEntryPoint(new ProblemAuthenticationEntryPoint())
                        .accessDeniedHandler(new ProblemAccessDeniedHandler()))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(new ProblemAuthenticationEntryPoint())
                        .accessDeniedHandler(new ProblemAccessDeniedHandler()))
                .cors(Customizer.withDefaults())
                // Stateless bearer-token API: no sessions, cookies, forms or CSRF tokens.
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOriginPatterns(properties.allowedOriginPatterns());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "If-Match", "If-None-Match",
                "traceparent", "tracestate"));
        cors.setExposedHeaders(List.of("ETag", "Location", "X-Correlation-Id", "X-RateLimit-Remaining", "Retry-After",
                "X-Data-Age"));
        // Bearer tokens travel in the Authorization header, never in cookies.
        cors.setAllowCredentials(false);
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }
}
