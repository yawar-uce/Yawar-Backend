package ec.yawar.gateway.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration(proxyBeanMethods = false)
class RateLimitConfig {

    /**
     * The limiter used by the {@code RequestRateLimiter} default filter. The Redis delegate is created here with the
     * project limits as its default configuration (the auto-configured {@code redisRateLimiter} bean keeps the
     * library defaults and stays unused, hence {@link Primary}); it finds the Redis template and the Lua script in
     * the application context.
     */
    @Bean
    @Primary
    ResilientRateLimiter resilientRateLimiter(RateLimitProperties properties, MeterRegistry meters,
                                              ApplicationContext context) {
        RedisRateLimiter redis = new RedisRateLimiter(properties.replenishPerSecond(), properties.burst(), properties.requestedTokens());
        redis.setApplicationContext(context);
        LocalTokenBucketLimiter local = new LocalTokenBucketLimiter(properties.replenishPerSecond(), properties.burst(),
                properties.requestedTokens());
        return new ResilientRateLimiter(redis, local, properties, meters);
    }

    @Bean
    KeyResolver userOrIpKeyResolver() {
        return new UserOrIpKeyResolver();
    }
}
