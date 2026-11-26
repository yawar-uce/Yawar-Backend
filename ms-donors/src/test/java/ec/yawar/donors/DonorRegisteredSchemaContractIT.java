package ec.yawar.donors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import ec.yawar.donors.domain.ConsentPurpose;
import ec.yawar.donors.domain.RegistrationSource;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * V-02: the message the outbox relay really publishes to RabbitMQ (full Yawar envelope, as consumers receive it)
 * validates against the DonorRegistered message of docs/asyncapi/ms-donors.yaml.
 */
class DonorRegisteredSchemaContractIT extends AbstractDonorsIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired AmqpAdmin admin;
    @Autowired RabbitTemplate rabbit;

    @Test
    void contract_donorRegisteredPayload_matchesAsyncApi() throws Exception {
        JsonNode asyncApi = ContractFiles.read(ContractFiles.ASYNCAPI);
        JsonNode channel = asyncApi.at("/channels/donorRegistered");

        // The relay drops events nobody is bound to, so bind a queue before the donor is registered.
        String exchange = channel.at("/bindings/amqp/exchange/name").asString();
        String routingKey = channel.get("address").asString();
        Queue queue = new Queue("contract-it." + UUID.randomUUID(), false, false, true);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(new TopicExchange(exchange, true, false)).with(routingKey));

        UUID sub = UUID.randomUUID();
        mvc.perform(get("/api/v1/donors/me").with(donorToken(sub))).andReturn();

        Message message = rabbit.receive(queue.getName(), 15_000);
        assertThat(message).as("DonorRegistered published to %s / %s", exchange, routingKey).isNotNull();
        JsonNode body = JSON.readTree(message.getBody());

        // Enums of the contract equal the code's.
        JsonNode schemas = asyncApi.at("/components/schemas");
        assertThat(texts(schemas.at("/DonorRegistered/properties/registrationSource/enum")))
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(RegistrationSource.values()).map(Enum::name).toList());
        assertThat(texts(schemas.at("/ConsentPurpose/enum")))
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(ConsentPurpose.values()).map(Enum::name).toList());

        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7,
                b -> b.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build()));
        List<Error> errors = registry.getSchema(messageSchema(asyncApi, "DonorRegistered")).validate(body);
        assertThat(errors).as("message %s", body).isEmpty();
        assertThat(body.get("producer").asString()).isEqualTo("ms-donors");
        assertThat(body.toString()).doesNotContain(sub.toString()).doesNotContain("@");
    }

    /** Schema document: the message payload (envelope + eventType + payload) with {@code components} for local refs. */
    private static JsonNode messageSchema(JsonNode asyncApi, String message) {
        ObjectNode root = ((ObjectNode) asyncApi.at("/components/messages/" + message + "/payload")).deepCopy();
        root.set("components", asyncApi.get("components"));
        return root;
    }

    private static List<String> texts(JsonNode array) {
        return array.valueStream().map(JsonNode::asString).toList();
    }
}
