package ec.yawar.donors.interfaces.rest;

import ec.yawar.donors.application.ConsentRequiredException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** RFC 7807 mapping of the domain errors of this service. Deliberately not a catch-all. */
@RestControllerAdvice
class ProblemExceptionHandler {

    @ExceptionHandler(ConsentRequiredException.class)
    ProblemDetail consentRequired(ConsentRequiredException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT,
                "Necesitamos tu consentimiento para tratar tus datos (LOPDP) y crear tu perfil de donante.");
        problem.setType(URI.create("https://yawar.ec/problems/consent-required"));
        problem.setTitle("Consentimiento requerido");
        return problem;
    }
}
