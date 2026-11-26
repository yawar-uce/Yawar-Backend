package ec.yawar.gateway.problem;

import ec.yawar.gateway.tracing.Correlation;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/** Writes RFC 7807 problem details ({@code application/problem+json}) for errors produced by the gateway itself. */
public final class Problems {

    public static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private Problems() {
    }

    /** The problem document; {@code correlationId} lets support find the request in the logs. */
    public static Map<String, Object> body(HttpStatus status, String detail, String instance, String correlationId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", status.getReasonPhrase());
        body.put("status", status.value());
        body.put("detail", detail);
        if (instance != null) {
            body.put("instance", instance);
        }
        if (correlationId != null) {
            body.put("correlationId", correlationId);
        }
        return body;
    }

    /** Sets status and headers, then writes the problem as the whole response. */
    public static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String detail,
                                   Map<String, String> headers) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.empty();
        }
        response.setStatusCode(status);
        HttpHeaders out = response.getHeaders();
        headers.forEach(out::set);
        out.setContentType(PROBLEM_JSON);
        String correlationId = exchange.getRequest().getHeaders().getFirst(Correlation.HTTP_HEADER);
        byte[] json = MAPPER.writeValueAsBytes(
                body(status, detail, exchange.getRequest().getPath().value(), correlationId));
        out.setContentLength(json.length);
        DataBuffer buffer = response.bufferFactory().wrap(json);
        return response.writeWith(Mono.just(buffer));
    }
}
