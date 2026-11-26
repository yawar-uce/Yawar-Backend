package ec.yawar.donors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** The database arbitrates concurrent first requests (UNIQUE user_id + ON CONFLICT DO NOTHING). */
class GetOrCreateDonorConcurrencyIT extends AbstractDonorsIT {

    private static final int PARALLEL = 20;

    @Test
    void twentyParallelFirstRequestsCreateOneDonorOneConsentOneEvent() throws Exception {
        UUID sub = UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(PARALLEL)) {
            for (int i = 0; i < PARALLEL; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return mvc.perform(get("/api/v1/donors/me").with(donorToken(sub))).andReturn().getResponse()
                            .getStatus();
                }));
            }
            start.countDown();
            for (Future<Integer> r : results) {
                assertThat(r.get()).isEqualTo(200);
            }
        }

        UUID donorId = jdbc.sql("SELECT id FROM donor WHERE user_id = ?").param(sub).query(UUID.class).single();
        assertThat(count("consent", "donor_id = ?", donorId)).isEqualTo(1);
        assertThat(count("outbox", "aggregate_id = ?", donorId.toString())).isEqualTo(1);
    }
}
