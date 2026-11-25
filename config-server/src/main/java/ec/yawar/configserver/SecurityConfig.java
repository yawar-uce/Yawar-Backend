package ec.yawar.configserver;

import static org.springframework.security.config.Customizer.withDefaults;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Least privilege with two HTTP Basic users from the environment:
 * <ul>
 *   <li>client ({@code CONFIG_SERVER_USER}): what every service uses; may only read {@code GET /config/**}</li>
 *   <li>admin ({@code CONFIG_ADMIN_USER}): may also call {@code POST /busrefresh} and read {@code /health}, {@code /info}</li>
 * </ul>
 * The probes are public. Clients are services, not browsers: stateless, no sessions and no CSRF tokens.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    static final String CLIENT = "CONFIG_CLIENT";
    static final String ADMIN = "CONFIG_ADMIN";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/health/live", "/health/ready").permitAll()
                        .requestMatchers(HttpMethod.GET, "/config/**").hasAnyRole(CLIENT, ADMIN)
                        .anyRequest().hasRole(ADMIN))
                .httpBasic(withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
    }

    @Bean
    UserDetailsService users(@Value("${CONFIG_SERVER_USER}") String clientUser,
                             @Value("${CONFIG_SERVER_PASSWORD}") String clientPassword,
                             @Value("${CONFIG_ADMIN_USER}") String adminUser,
                             @Value("${CONFIG_ADMIN_PASSWORD}") String adminPassword) {
        PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        return new InMemoryUserDetailsManager(
                User.withUsername(clientUser).password(encoder.encode(clientPassword)).roles(CLIENT).build(),
                User.withUsername(adminUser).password(encoder.encode(adminPassword)).roles(ADMIN).build());
    }
}
