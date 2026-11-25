package ec.yawar.platform.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class DeadlineTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-25T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void remainingNeverNegative() {
        Deadline d = Deadline.in(Duration.ofMillis(300), clock);
        Clock later = Clock.offset(clock, Duration.ofSeconds(1));

        assertThat(d.remaining(clock)).isEqualTo(Duration.ofMillis(300));
        assertThat(d.remaining(later)).isZero();
        assertThat(d.isExpired(later)).isTrue();
        assertThat(d.isExpired(clock)).isFalse();
    }

    @Test
    void bindIsScopedAndNests() {
        Deadline outer = Deadline.in(Duration.ofSeconds(5), clock);
        Deadline inner = Deadline.in(Duration.ofSeconds(1), clock);

        try (Deadline.Scope a = Deadline.bind(outer)) {
            try (Deadline.Scope b = Deadline.bind(inner)) {
                assertThat(Deadline.current()).contains(inner);
            }
            assertThat(Deadline.current()).contains(outer);
        }
        assertThat(Deadline.current()).isEmpty();
    }

    private Deadline filterWith(String header) throws Exception {
        DeadlineFilter filter = new DeadlineFilter(clock, Duration.ofSeconds(30));
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (header != null) {
            request.addHeader(Deadline.HEADER, header);
        }
        AtomicReference<Deadline> seen = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seen.set(Deadline.current().orElse(null));
            }
        });
        assertThat(Deadline.current()).isEmpty();
        return seen.get();
    }

    @Test
    void filterBindsBudgetFromHeader() throws Exception {
        assertThat(filterWith("1500").remaining(clock)).isEqualTo(Duration.ofMillis(1500));
    }

    @Test
    void filterCapsHugeBudget() throws Exception {
        assertThat(filterWith("999999999").remaining(clock)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void filterIgnoresMissingOrInvalidHeader() throws Exception {
        assertThat(filterWith(null)).isNull();
        assertThat(filterWith("abc")).isNull();
        assertThat(filterWith("-5")).isNull();
        assertThat(filterWith(" ")).isNull();
    }

    @Test
    void filterOrderIsRightAfterCorrelation() {
        assertThat(new DeadlineFilter(clock, Duration.ofSeconds(1)).getOrder())
                .isGreaterThan(ec.yawar.platform.tracing.CorrelationIdFilter.ORDER);
    }
}
