package ec.yawar.configserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/** Config server with the native backend (test repo) and a real RabbitMQ 4.2 for Spring Cloud Bus. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"native", "it"})
@Testcontainers
class ConfigServerIT {

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.2.9-management-alpine");

    @LocalServerPort int port;
    @Autowired RabbitAdmin admin;
    @Autowired RabbitTemplate rabbit;

    private RestClient client(boolean authenticated) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { });
        if (authenticated) {
            builder.defaultHeaders(h -> h.setBasicAuth("it-config", "it-secret"));
        }
        return builder.build();
    }

    @Test
    void servesMergedConfigurationOfAServiceAndProfile() {
        ResponseEntity<String> res = client(true).get().uri("/config/ms-donors/dev").retrieve().toEntity(String.class);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).contains("\"name\":\"ms-donors\"", "from-ms-donors-dev", "from-ms-donors",
                "from-application");
    }

    @Test
    void configurationRequiresCredentials() {
        assertThat(client(false).get().uri("/config/ms-donors/dev").retrieve().toBodilessEntity()
                .getStatusCode().value()).isEqualTo(401);
        ResponseEntity<Void> wrong = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { })
                .build().get().uri("/config/ms-donors/dev")
                .header(HttpHeaders.AUTHORIZATION, "Basic " + java.util.Base64.getEncoder()
                        .encodeToString("it-config:wrong".getBytes(StandardCharsets.UTF_8)))
                .retrieve().toBodilessEntity();
        assertThat(wrong.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void probesArePublicAndUp() {
        for (String path : new String[] {"/health/live", "/health/ready"}) {
            ResponseEntity<String> res = client(false).get().uri(path).retrieve().toEntity(String.class);
            assertThat(res.getStatusCode().value()).as(path).isEqualTo(200);
            assertThat(res.getBody()).as(path).contains("\"status\":\"UP\"");
        }
    }

    @Test
    void busRefreshBroadcastsARefreshEventOverRabbitMq() {
        Queue queue = new AnonymousQueue();
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(new TopicExchange("springCloudBus")).with("#"));

        ResponseEntity<Void> res = client(true).post().uri("/busrefresh").retrieve().toBodilessEntity();

        assertThat(res.getStatusCode().value()).isEqualTo(204);
        Message message = await().atMost(Duration.ofSeconds(10)).until(() -> rabbit.receive(queue.getName()), m -> m != null);
        assertThat(new String(message.getBody(), StandardCharsets.UTF_8)).contains("RefreshRemoteApplicationEvent");
    }

    @Test
    void busRefreshRequiresCredentials() {
        assertThat(client(false).post().uri("/busrefresh").retrieve().toBodilessEntity()
                .getStatusCode().value()).isEqualTo(401);
    }
}
