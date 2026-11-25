package ec.yawar.configserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

/**
 * Configuration of every Yawar service, read from the private repository {@code yawar-uce/yawar-config}
 * (profile {@code git}) or a local folder (profile {@code native}). {@code POST /busrefresh} tells all
 * services, through Spring Cloud Bus on RabbitMQ, to reload their {@code @RefreshScope} beans.
 */
@SpringBootApplication
@EnableConfigServer
public class ConfigServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConfigServerApplication.class, args);
    }
}
