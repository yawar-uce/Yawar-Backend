package ec.yawar.gateway.tracing;

import io.micrometer.context.ContextRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class TracingConfig {

    /** Registers the MDC accessor once; used with {@code spring.reactor.context-propagation: auto}. */
    @Bean
    static MdcRegistration mdcCorrelationRegistration() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(new MdcCorrelationAccessor());
        return new MdcRegistration();
    }

    /** Marker so the registration is a bean (and runs at startup). */
    record MdcRegistration() {
    }
}
