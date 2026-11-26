package ec.yawar.donors.interfaces.openapi;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/** OpenAPI 3 contract of ms-donors (RNF-10), served at {@code /v3/api-docs}. */
@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(
        info = @Info(title = "ms-donors API", version = "0.1.0",
                description = "Donor profile, consents (LOPDP) and eligibility. Reached through the API Gateway."),
        security = @SecurityRequirement(name = "keycloak"))
@SecurityScheme(name = "keycloak", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
class OpenApiConfig {
}
