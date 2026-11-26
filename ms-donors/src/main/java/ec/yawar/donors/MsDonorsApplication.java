package ec.yawar.donors;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ms-donors (YAW-DOC-07 §7.3.1): donor profile, consents (LOPDP) and eligibility. Hexagonal layout:
 * {@code domain} (no framework code) ← {@code application} (use cases, ports) ← {@code infrastructure}
 * (adapters) and {@code interfaces} (REST). Integrates with other services only through RabbitMQ (C-02).
 */
@SpringBootApplication
public class MsDonorsApplication {

    public static void main(String[] args) {
        SpringApplication.run(MsDonorsApplication.class, args);
    }
}
