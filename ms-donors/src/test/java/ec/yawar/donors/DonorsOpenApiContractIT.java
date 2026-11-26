package ec.yawar.donors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * V-02: the OpenAPI document served by the running service is the committed {@code docs/openapi/ms-donors.yaml}.
 * After an intentional API change regenerate it with
 * {@code make contract S=ms-donors CONTRACT_UPDATE=true} (or {@code -Dcontract.update=true}) and commit the diff.
 */
class DonorsOpenApiContractIT extends AbstractDonorsIT {

    @Test
    void contract_openapi_matchesCommittedSpec() throws Exception {
        var response = mvc.perform(get("/v3/api-docs")).andReturn().getResponse();
        assertThat(response.getStatus()).as("GET /v3/api-docs").isEqualTo(200);
        String served = response.getContentAsString();
        JsonNode actual = ContractFiles.YAML.readTree(served);

        if (Boolean.getBoolean("contract.update")) {
            assertThat(System.getenv("CI")).as("-Dcontract.update=true must never run in CI").isNull();
            ContractFiles.write(ContractFiles.OPENAPI, actual);
            return;
        }
        Path generated = Path.of("target", "openapi-actual.yaml");
        ContractFiles.write(generated, actual);
        assertThat(Files.exists(ContractFiles.OPENAPI))
                .as("docs/openapi/ms-donors.yaml is missing: run with -Dcontract.update=true").isTrue();
        // JsonNode equality ignores key order; arrays keep theirs.
        assertThat(actual)
                .as("The served OpenAPI differs from docs/openapi/ms-donors.yaml. The served spec was written to "
                        + "ms-donors/target/openapi-actual.yaml. If the change is intended run: "
                        + "make contract S=ms-donors CONTRACT_UPDATE=true  (or mvn verify -Dcontract.update=true) "
                        + "and commit docs/openapi/ms-donors.yaml.")
                .isEqualTo(ContractFiles.read(ContractFiles.OPENAPI));
    }
}
