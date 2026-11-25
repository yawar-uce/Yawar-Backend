package ec.yawar.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.netflix.eureka.cluster.PeerEurekaNodes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * With the shared client settings every service gets from yawar-config (credentialed defaultZone,
 * prefer-ip-address), the server's own settings in discovery-service.yml must still win: it has no peers,
 * so it never replicates to itself or logs a URL with credentials.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.instance.prefer-ip-address=false",
        "eureka.instance.hostname=localhost",
        "eureka.client.service-url.defaultZone=http://localhost:${server.port}/eureka/"})
@ActiveProfiles("it")
class SingleNodeRegistryIT {

    @Autowired PeerEurekaNodes peers;

    @Test
    void serverHasNoPeerNodes() {
        assertThat(peers.getPeerEurekaNodes()).isEmpty();
        assertThat(peers.getPeerNodesView()).isEmpty();
    }
}
