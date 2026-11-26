package ec.yawar.gateway;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;

/** Adds a real Redis (started once for all test classes) behind the rate limiter. */
public abstract class GatewayRedisITSupport extends GatewayITSupport {

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2-alpine").withExposedPorts(6379);

    static {
        REDIS.start();
    }
}
