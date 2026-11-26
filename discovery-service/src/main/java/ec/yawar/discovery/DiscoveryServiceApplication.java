package ec.yawar.discovery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

/**
 * Eureka server of the Yawar platform (YAW-217). Services register with {@code prefer-ip-address: true};
 * the API Gateway resolves its {@code lb://} routes through it. Runs on top of Kubernetes as a course
 * requirement, so Kubernetes Services remain the fallback for in-cluster DNS.
 */
@SpringBootApplication
@EnableEurekaServer
public class DiscoveryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DiscoveryServiceApplication.class, args);
    }
}
