package ec.yawar.discovery;

import static org.springframework.security.config.Customizer.withDefaults;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * HTTP Basic ({@code EUREKA_USER} / {@code EUREKA_PASSWORD}) on the dashboard and the registry API; the probes
 * are public. Eureka clients are services that register with POST/PUT/DELETE on {@code /eureka/**} and carry no
 * CSRF token, so CSRF is ignored there only; the browser dashboard keeps it.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/health/live", "/health/ready").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(withDefaults())
                .csrf(csrf -> csrf.ignoringRequestMatchers("/eureka/**"))
                .build();
    }
}
