package ec.yawar.donors.interfaces.openapi;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

/** OpenAPI 3 contract of ms-donors (RNF-10), served at {@code /v3/api-docs}. */
@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(
        info = @Info(title = "ms-donors API", version = "0.1.0",
                description = "Donor profile, consents (LOPDP) and eligibility. Reached through the API Gateway.\n\n"
                        + "Implemented: `GET /api/v1/donors/me`. The donor's consents are returned inside that response; "
                        + "`PUT /api/v1/donors/me` (If-Match) and `POST /api/v1/donors/me/consents` are planned "
                        + "(HU-02..HU-05) and not part of this contract yet."),
        servers = @Server(url = "http://localhost:8000", description = "API Gateway (local; every environment uses its own host)"),
        security = @SecurityRequirement(name = "keycloak"))
@SecurityScheme(name = "keycloak", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT",
        description = "Keycloak access token (Authorization Code + PKCE), audience yawar-api")
class OpenApiConfig {
}
