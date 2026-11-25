package ec.yawar.platform.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    private String runWith(String header, AtomicReference<String> seenInMdc) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/donors/me");
        if (header != null) {
            request.addHeader(Correlation.HTTP_HEADER, header);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seenInMdc.set(MDC.get(Correlation.MDC_CORRELATION_ID));
            }
        });
        return response.getHeader(Correlation.HTTP_HEADER);
    }

    @Test
    void keepsTheGatewayCorrelationId() throws Exception {
        AtomicReference<String> mdc = new AtomicReference<>();

        String echoed = runWith("req-8812", mdc);

        assertThat(echoed).isEqualTo("req-8812");
        assertThat(mdc.get()).isEqualTo("req-8812");
        assertThat(MDC.get(Correlation.MDC_CORRELATION_ID)).isNull();
    }

    @Test
    void createsOneWhenMissing() throws Exception {
        AtomicReference<String> mdc = new AtomicReference<>();

        String echoed = runWith(null, mdc);

        assertThat(echoed).isNotBlank().isEqualTo(mdc.get());
    }

    @Test
    void replacesUnsafeValueToAvoidLogInjection() throws Exception {
        AtomicReference<String> mdc = new AtomicReference<>();

        String echoed = runWith("evil\nINFO fake log line", mdc);

        assertThat(echoed).doesNotContain("\n").isNotEqualTo("evil\nINFO fake log line");
    }

    @Test
    void runsBeforeSpringSecurity() {
        assertThat(filter.getOrder()).isLessThan(-100);
    }

    @Test
    void sanitizeAcceptsOnlySafeIds() {
        assertThat(Correlation.sanitize("abc-123_x.y:z")).isEqualTo("abc-123_x.y:z");
        assertThat(Correlation.sanitize("a".repeat(129))).isNull();
        assertThat(Correlation.sanitize("with space")).isNull();
        assertThat(Correlation.sanitize(null)).isNull();
    }
}
