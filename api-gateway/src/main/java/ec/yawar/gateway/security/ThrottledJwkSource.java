package ec.yawar.gateway.security;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * JWKS source with a bounded refresh rate. Spring's default source refetches the key set whenever a token names an
 * unknown {@code kid}, so a client sending tokens with random key ids would make the gateway call Keycloak once per
 * request. Here the set is fetched at most once per {@code minRefreshInterval} (30 s) when a {@code kid} is unknown
 * and once per {@code maxAge} (5 min) to notice revoked keys; concurrent callers share one fetch. If a refresh fails
 * the previous set keeps being used (Keycloak down: cached keys, YAW-DOC-07 §9.5).
 */
public class ThrottledJwkSource implements Function<SignedJWT, Flux<JWK>> {

    private static final Logger log = LoggerFactory.getLogger(ThrottledJwkSource.class);

    private final String jwkSetUri;
    private final WebClient webClient;
    private final long minRefreshNanos;
    private final long maxAgeNanos;
    private final LongSupplier nanoClock;

    private volatile JWKSet cached;
    private volatile long fetchedAt;
    private volatile long lastAttempt;
    private volatile boolean attempted;
    private Mono<JWKSet> inflight;

    public ThrottledJwkSource(String jwkSetUri, WebClient webClient, Duration minRefreshInterval, Duration maxAge) {
        this(jwkSetUri, webClient, minRefreshInterval, maxAge, System::nanoTime);
    }

    ThrottledJwkSource(String jwkSetUri, WebClient webClient, Duration minRefreshInterval, Duration maxAge,
                       LongSupplier nanoClock) {
        this.jwkSetUri = jwkSetUri;
        this.webClient = webClient;
        this.minRefreshNanos = minRefreshInterval.toNanos();
        this.maxAgeNanos = maxAge.toNanos();
        this.nanoClock = nanoClock;
    }

    @Override
    public Flux<JWK> apply(SignedJWT jwt) {
        JWKSelector selector = new JWKSelector(JWKMatcher.forJWSHeader(jwt.getHeader()));
        JWKSet current = cached;
        long now = nanoClock.getAsLong();
        List<JWK> known = current == null ? List.of() : selector.select(current);
        boolean stale = current != null && now - fetchedAt > maxAgeNanos;
        boolean mayRefresh = !attempted || now - lastAttempt >= minRefreshNanos;
        if (current != null && ((!known.isEmpty() && !stale) || !mayRefresh)) {
            return Flux.fromIterable(known);
        }
        if (current == null && !mayRefresh) {
            return Flux.error(new IllegalStateException("JWKS not available yet, retry later"));
        }
        return refresh(now)
                .map(selector::select)
                .onErrorResume(error -> current == null ? Mono.error(error) : Mono.just(known))
                .flatMapMany(Flux::fromIterable);
    }

    private synchronized Mono<JWKSet> refresh(long now) {
        if (inflight != null) {
            return inflight;
        }
        attempted = true;
        lastAttempt = now;
        inflight = webClient.get().uri(jwkSetUri).retrieve().bodyToMono(String.class)
                .map(ThrottledJwkSource::parse)
                .doOnNext(set -> {
                    cached = set;
                    fetchedAt = nanoClock.getAsLong();
                })
                .doOnError(error -> log.warn("JWKS refresh failed ({}); using the cached keys if any",
                        error.getClass().getSimpleName()))
                .doFinally(signal -> clearInflight())
                .cache();
        return inflight;
    }

    private synchronized void clearInflight() {
        inflight = null;
    }

    private static JWKSet parse(String body) {
        try {
            return JWKSet.parse(body);
        } catch (java.text.ParseException e) {
            throw new IllegalStateException("Invalid JWKS document", e);
        }
    }
}
