package ec.yawar.gateway.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ResilienceConfig {

    static final String MS_DONORS = "ms-donors";

    @Bean
    Customizer<ReactiveResilience4JCircuitBreakerFactory> msDonorsCircuitBreaker(CircuitBreakerProperties p) {
        return factory -> factory.configure(builder -> builder
                .circuitBreakerConfig(CircuitBreakerConfig.custom()
                        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                        .slidingWindowSize(p.slidingWindowSize())
                        .minimumNumberOfCalls(p.slidingWindowSize())
                        .failureRateThreshold(p.failureRateThreshold())
                        .waitDurationInOpenState(p.waitInOpenState())
                        .permittedNumberOfCallsInHalfOpenState(p.halfOpenCalls())
                        .build())
                .timeLimiterConfig(TimeLimiterConfig.custom().timeoutDuration(p.timeLimit()).build())
                .build(), MS_DONORS);
    }
}
