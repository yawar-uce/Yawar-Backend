package ec.yawar.donors;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLWriteFeature;

/** Locates and reads the committed contracts under {@code docs/} (tests run with the module as working dir). */
final class ContractFiles {

    static final Path DOCS = Path.of("..", "docs").toAbsolutePath().normalize();
    static final Path OPENAPI = DOCS.resolve("openapi/ms-donors.yaml");
    static final Path ASYNCAPI = DOCS.resolve("asyncapi/ms-donors.yaml");

    /** YAML with alphabetically sorted keys, so the committed file is stable and diffs are small. */
    static final ObjectMapper YAML = YAMLMapper.builder()
            .enable(JsonNodeFeature.WRITE_PROPERTIES_SORTED)
            .disable(YAMLWriteFeature.WRITE_DOC_START_MARKER)
            .enable(YAMLWriteFeature.MINIMIZE_QUOTES)
            .build();

    private ContractFiles() {
    }

    static JsonNode read(Path file) {
        try {
            return YAML.readTree(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    static void write(Path file, JsonNode tree) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, YAML.writeValueAsString(tree));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
    }
}
