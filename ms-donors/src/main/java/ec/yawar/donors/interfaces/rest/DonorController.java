package ec.yawar.donors.interfaces.rest;

import ec.yawar.donors.application.DonorProfile;
import ec.yawar.donors.application.GetOrCreateDonorUseCase;
import ec.yawar.donors.domain.RegistrationSource;
import ec.yawar.donors.domain.UserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Arrays;
import java.util.Collection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/donors")
@Tag(name = "Donors")
class DonorController {

    private static final String PROBLEM = MediaType.APPLICATION_PROBLEM_JSON_VALUE;

    private final GetOrCreateDonorUseCase getOrCreateDonor;

    DonorController(GetOrCreateDonorUseCase getOrCreateDonor) {
        this.getOrCreateDonor = getOrCreateDonor;
    }

    @GetMapping("/me")
    @Operation(operationId = "getMyDonorProfile",
            summary = "Own donor profile; created on the first call from the privacy consent in the token",
            description = "Returns the caller's donor profile, found by the token's `sub` (never by an id in the path). "
                    + "The first call creates it when the token carries `privacy_consent=accepted`. "
                    + "The active consents are part of this response.")
    @ApiResponse(responseCode = "200", description = "The caller's donor profile",
            headers = @Header(name = HttpHeaders.ETAG, description = "Profile version, e.g. \"0\"; send it back as If-None-Match",
                    schema = @Schema(type = "string")),
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = DonorResponse.class)))
    @ApiResponse(responseCode = "304", description = "If-None-Match matches the current version; no body",
            headers = @Header(name = HttpHeaders.ETAG, schema = @Schema(type = "string")), content = @Content)
    @ApiResponse(responseCode = "401", description = "Missing or invalid token; empty body, WWW-Authenticate header",
            content = @Content)
    @ApiResponse(responseCode = "403", description = "The token has no donor role; empty body",
            content = @Content)
    @ApiResponse(responseCode = "422", description = "The token carries no accepted privacy consent; nothing is stored",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<DonorResponse> me(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
                                     @Parameter(description = "ETag of a previous response; a match answers 304")
                                     @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        DonorProfile profile = getOrCreateDonor.execute(UserId.fromSubject(jwt.getSubject()),
                consentAccepted(jwt.getClaims().get("privacy_consent")), source(jwt.getClaimAsString("idp")));
        String etag = "\"" + profile.donor().version() + "\"";
        if (matches(ifNoneMatch, etag)) {
            return ResponseEntity.status(304).eTag(etag).build();
        }
        return ResponseEntity.ok().eTag(etag).body(DonorResponse.from(profile));
    }

    /** Keycloak emits the multivalued attribute as a list; a plain string or boolean is accepted too. */
    static boolean consentAccepted(Object claim) {
        if (claim instanceof Boolean b) {
            return b;
        }
        if (claim instanceof Collection<?> values) {
            return values.stream().anyMatch(v -> "accepted".equals(String.valueOf(v)) || "true".equals(String.valueOf(v)));
        }
        return claim != null && Arrays.asList("accepted", "true").contains(claim.toString());
    }

    static RegistrationSource source(String idp) {
        return "google".equalsIgnoreCase(idp) ? RegistrationSource.GOOGLE : RegistrationSource.PASSWORD;
    }

    private static boolean matches(String header, String etag) {
        if (header == null) {
            return false;
        }
        return Arrays.stream(header.split(",")).map(String::trim).map(v -> v.startsWith("W/") ? v.substring(2) : v)
                .anyMatch(v -> v.equals("*") || v.equals(etag));
    }
}
