package ec.yawar.donors.interfaces.rest;

import ec.yawar.donors.application.DonorProfile;
import ec.yawar.donors.application.GetOrCreateDonorUseCase;
import ec.yawar.donors.domain.RegistrationSource;
import ec.yawar.donors.domain.UserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Arrays;
import java.util.Collection;
import org.springframework.http.HttpHeaders;
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

    private final GetOrCreateDonorUseCase getOrCreateDonor;

    DonorController(GetOrCreateDonorUseCase getOrCreateDonor) {
        this.getOrCreateDonor = getOrCreateDonor;
    }

    @GetMapping("/me")
    @Operation(summary = "Own donor profile; created on the first call from the privacy consent in the token")
    @ApiResponse(responseCode = "200", description = "The caller's donor profile, with ETag")
    @ApiResponse(responseCode = "304", description = "If-None-Match matches the current version")
    @ApiResponse(responseCode = "401", description = "Missing or invalid token")
    @ApiResponse(responseCode = "403", description = "The token has no donor role")
    @ApiResponse(responseCode = "422", description = "The token carries no accepted privacy consent")
    ResponseEntity<DonorResponse> me(@AuthenticationPrincipal Jwt jwt,
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
