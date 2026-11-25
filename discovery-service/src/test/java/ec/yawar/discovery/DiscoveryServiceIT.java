package ec.yawar.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

/** Eureka server on a random port: security, probes, and a service registering through the REST API. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
class DiscoveryServiceIT {

    private static final String INSTANCE = """
            {"instance":{"instanceId":"ms-donors:10.0.0.5:8081","hostName":"10.0.0.5","app":"MS-DONORS",
             "ipAddr":"10.0.0.5","status":"UP","port":{"$":8081,"@enabled":"true"},
             "dataCenterInfo":{"@class":"com.netflix.appinfo.InstanceInfo$DefaultDataCenterInfo","name":"MyOwn"}}}
            """;

    @LocalServerPort int port;

    private RestClient client(boolean authenticated) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { });
        if (authenticated) {
            builder.defaultHeaders(h -> h.setBasicAuth("it-eureka", "it-secret"));
        }
        return builder.build();
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
    void registryRequiresCredentials() {
        assertThat(client(false).get().uri("/eureka/apps").retrieve().toBodilessEntity().getStatusCode().value())
                .isEqualTo(401);
        assertThat(client(false).post().uri("/eureka/apps/MS-DONORS").contentType(MediaType.APPLICATION_JSON)
                .body(INSTANCE).retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void serviceRegistersWithItsIpAddressAndIsListed() {
        ResponseEntity<Void> registered = client(true).post().uri("/eureka/apps/MS-DONORS")
                .contentType(MediaType.APPLICATION_JSON).body(INSTANCE).retrieve().toBodilessEntity();
        assertThat(registered.getStatusCode().value()).isEqualTo(204);

        ResponseEntity<String> app = client(true).get().uri("/eureka/apps/MS-DONORS")
                .accept(MediaType.APPLICATION_JSON).retrieve().toEntity(String.class);
        assertThat(app.getStatusCode().value()).isEqualTo(200);
        assertThat(app.getBody()).contains("\"ipAddr\":\"10.0.0.5\"", "\"status\":\"UP\"");
    }

    @Test
    void dashboardNeedsLoginAndShowsEureka() {
        assertThat(client(false).get().uri("/").retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(401);
        ResponseEntity<String> page = client(true).get().uri("/").retrieve().toEntity(String.class);
        assertThat(page.getStatusCode().value()).isEqualTo(200);
        assertThat(page.getBody()).contains("Eureka");
    }
}
