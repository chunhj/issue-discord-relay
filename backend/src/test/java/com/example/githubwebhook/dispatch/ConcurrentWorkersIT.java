package com.example.githubwebhook.dispatch;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.githubwebhook.discord.DiscordClient;
import com.example.githubwebhook.support.IntegrationTestBase;
import com.example.githubwebhook.support.SignedRequests;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

/** SC4: several workers never send a job twice, the HTTP call holds no DB connection, ingest ignores Discord. */
class ConcurrentWorkersIT extends IntegrationTestBase {

    @Autowired
    DispatchWorker worker;

    @Autowired
    DiscordClient discord;

    @Autowired
    DataSource dataSource;

    @Autowired
    JsonMapper json;

    private void stubDiscordOk() {
        WIREMOCK.stubFor(WireMock.post(urlPathEqualTo(DISCORD_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"id\":\"1234567890123456789\"}")));
    }

    private Connection rawConnection() throws Exception {
        // Bypasses the application pool, so observing the job never changes the pool's active count.
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @RepeatedTest(3)
    void two_workers_deliver_each_issue_once() throws Exception {
        int issues = 20;
        stubDiscordOk();
        List<String> expectedUrls = new ArrayList<>();
        for (int i = 0; i < issues; i++) {
            int number = 8000 + i;
            expectedUrls.add("https://github.com/octo/repo/issues/" + number);
            assertThat(postSigned("issues", "delivery-c" + number, SignedRequests.issuesOpened(number, "Issue " + number))
                    .statusCode()).isEqualTo(202);
        }

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int t = 0; t < 2; t++) {
                Callable<Integer> poller = () -> {
                    barrier.await();
                    int handled = 0;
                    int n;
                    while ((n = worker.pollOnce()) > 0) {
                        handled += n;
                    }
                    return handled;
                };
                futures.add(pool.submit(poller));
            }
            int total = 0;
            for (Future<Integer> f : futures) {
                total += f.get(30, TimeUnit.SECONDS);
            }
            assertThat(total).isEqualTo(issues);
        } finally {
            pool.shutdownNow();
        }

        List<String> sentUrls = new ArrayList<>();
        for (var request : WIREMOCK.findAll(postRequestedFor(urlPathEqualTo(DISCORD_PATH)))) {
            sentUrls.add(json.readTree(new String(request.getBody(), StandardCharsets.UTF_8))
                    .get("embeds").get(0).get("url").asString());
        }
        // Size 20 plus exact-set equality: no duplicate and no miss.
        assertThat(sentUrls).hasSize(issues).containsExactlyInAnyOrderElementsOf(expectedUrls);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dispatch_jobs WHERE status = 'SENT' AND attempts = 1",
                Integer.class)).isEqualTo(issues);
    }

    @Test
    void send_happens_outside_any_transaction() throws Exception {
        long delayMs = 800; // below the 1 s read timeout of the test base
        // Cold client start (class loading, first connect) would eat the 200 ms of headroom, so warm it up first.
        stubDiscordOk();
        discord.send("octo/repo", 1, "warm-up", "https://github.com/octo/repo/issues/1");
        WIREMOCK.resetAll();
        WIREMOCK.stubFor(WireMock.post(urlPathEqualTo(DISCORD_PATH)).willReturn(aResponse().withStatus(200)
                .withFixedDelay((int) delayMs).withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"1234567890123456789\"}")));
        assertThat(postSigned("issues", "delivery-h1", SignedRequests.issuesOpened(8101, "Slow")).statusCode())
                .isEqualTo(202);
        var pool = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection raw = rawConnection(); Statement st = raw.createStatement()) {
            Future<Integer> poll = executor.submit(worker::pollOnce);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!"PROCESSING".equals(status(st))) {
                assertThat(System.nanoTime()).as("job never became PROCESSING").isLessThan(deadline);
                Thread.sleep(5);
            }

            long start = System.nanoTime();
            int minActive = Integer.MAX_VALUE;
            boolean processingWhileIdle = false;
            while (!poll.isDone()) {
                int active = pool.getActiveConnections();
                minActive = Math.min(minActive, active);
                if (active == 0 && "PROCESSING".equals(status(st))) {
                    processingWhileIdle = true;
                }
                Thread.sleep(20);
            }
            long windowMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertThat(poll.get()).isEqualTo(1);
            // 100 ms slack: the window opens when the 5 ms status poll sees PROCESSING, which can trail the send start.
            assertThat(windowMs).as("sampled window must cover the Discord delay")
                    .isGreaterThanOrEqualTo(delayMs - 100);
            assertThat(minActive).as("no connection held while Discord is in flight").isZero();
            assertThat(processingWhileIdle).as("job visible as PROCESSING from another connection while pool idle")
                    .isTrue();
            assertThat(status(st)).as(jdbc.queryForObject("SELECT last_error FROM dispatch_jobs", String.class))
                    .isEqualTo("SENT");
        } finally {
            executor.shutdownNow();
        }
    }

    private static String status(Statement st) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT status FROM dispatch_jobs")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    @Test
    void ingest_is_independent_of_discord() throws Exception {
        // no stub: WireMock would answer 404 if it were ever called

        assertThat(postSigned("issues", "delivery-i1", SignedRequests.issuesOpened(8201, "No Discord")).statusCode())
                .isEqualTo(202);

        assertThat(WIREMOCK.getAllServeEvents()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM dispatch_jobs", String.class)).isEqualTo("PENDING");
    }
}
