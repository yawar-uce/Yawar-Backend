package ec.yawar.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * API Gateway (YAW-DOC-07 §7.2): the only entry point of clients. Validates the JWT, applies role-based access and
 * the per-user rate limit, protects each route with a circuit breaker and starts the trace. It never contains
 * business logic and talks to the services only through {@code lb://} routes resolved by Eureka.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
