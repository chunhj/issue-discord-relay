package com.example.githubwebhook.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.githubwebhook.support.IntegrationTestBase;
import com.example.githubwebhook.support.SignedRequests;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class IdempotencyIT extends IntegrationTestBase {

    private static final String DUPLICATE = "{\"status\":\"duplicate\"}";

    @Autowired
    private WebhookEventRepository events;

    @Autowired
    private TransactionTemplate tx;

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private int insert(String deliveryId) {
        return tx.execute(status -> events.insertIfAbsent(deliveryId, "issues", "opened", "octo/repo", 1L, "t",
                "https://github.com/octo/repo/issues/1"));
    }

    @Test
    void insertIfAbsent_returns_one_then_zero() {
        assertThat(insert("d-repo")).isEqualTo(1);
        assertThat(insert("d-repo")).isEqualTo(0);

        assertThat(count("SELECT count(*) FROM webhook_events")).isEqualTo(1);
    }

    @Test
    void sequential_duplicate_creates_no_second_job() throws Exception {
        byte[] body = SignedRequests.issuesOpened(1, "twice");

        HttpResponse<String> first = postSigned("issues", "d-seq", body);
        HttpResponse<String> second = postSigned("issues", "d-seq", body);

        assertThat(first.statusCode()).isEqualTo(202);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(second.body()).isEqualTo(DUPLICATE);
        assertThat(count("SELECT count(*) FROM webhook_events")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM dispatch_jobs")).isEqualTo(1);
    }

    @RepeatedTest(3)
    void hundred_concurrent_same_delivery_creates_one_job() throws Exception {
        byte[] body = SignedRequests.issuesOpened(1, "race");
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> results = new ArrayList<>();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 100; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    HttpResponse<String> response = postSigned("issues", "d-race", body);
                    if (response.statusCode() == 200) {
                        // A duplicate answer must never precede the winner's commit (design 6.2 / assumption A4).
                        assertThat(count("SELECT count(*) FROM webhook_events WHERE delivery_id = ?", "d-race"))
                                .isEqualTo(1);
                    }
                    return response;
                }));
            }
            start.countDown();
        }

        int accepted = 0;
        int duplicates = 0;
        for (Future<HttpResponse<String>> f : results) {
            HttpResponse<String> response = f.get();
            if (response.statusCode() == 202) {
                accepted++;
            } else {
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.body()).isEqualTo(DUPLICATE);
                duplicates++;
            }
        }
        assertThat(accepted).isEqualTo(1);
        assertThat(duplicates).isEqualTo(99);
        assertThat(count("SELECT count(*) FROM webhook_events WHERE delivery_id = ?", "d-race")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM dispatch_jobs")).isEqualTo(1);
    }

    @Test
    void distinct_concurrent_deliveries_are_independent() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 20; i++) {
                int n = i;
                results.add(pool.submit(() -> {
                    start.await();
                    return postSigned("issues", "d-distinct-" + n, SignedRequests.issuesOpened(n + 1, "t" + n))
                            .statusCode();
                }));
            }
            start.countDown();
        }

        for (Future<Integer> f : results) {
            assertThat(f.get()).isEqualTo(202);
        }
        assertThat(count("SELECT count(*) FROM webhook_events")).isEqualTo(20);
        assertThat(count("SELECT count(*) FROM dispatch_jobs")).isEqualTo(20);
    }
}
