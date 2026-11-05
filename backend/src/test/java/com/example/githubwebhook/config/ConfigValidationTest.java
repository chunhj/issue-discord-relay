package com.example.githubwebhook.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** Docker-free: binds only AppProperties, so a datasource failure cannot mask the binding failure (D-09, D-17). */
class ConfigValidationTest {

    private static final String SECRET = "super-secret-value";
    private static final String URL = "https://discord.com/api/webhooks/1/token-value";

    @Configuration
    @EnableConfigurationProperties(AppProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    private ApplicationContextRunner valid() {
        return runner.withPropertyValues(
                "app.github.webhook-secret=" + SECRET,
                "app.discord.webhook-url=" + URL,
                "app.worker.enabled=true",
                "app.worker.poll-interval-ms=1000",
                "app.worker.batch-size=10",
                "app.worker.lease-seconds=30");
    }

    @Test
    void blank_secret_fails_startup() {
        valid().withPropertyValues("app.github.webhook-secret=").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasStackTraceContaining("webhookSecret");
        });
    }

    @Test
    void absent_secret_fails_startup() {
        runner.withPropertyValues("app.discord.webhook-url=" + URL, "app.worker.enabled=true",
                "app.worker.poll-interval-ms=1000", "app.worker.batch-size=10", "app.worker.lease-seconds=30")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void blank_discord_url_fails_startup() {
        valid().withPropertyValues("app.discord.webhook-url=").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasStackTraceContaining("webhookUrl");
        });
    }

    @Test
    void absent_discord_url_fails_startup() {
        runner.withPropertyValues("app.github.webhook-secret=" + SECRET, "app.worker.enabled=true",
                "app.worker.poll-interval-ms=1000", "app.worker.batch-size=10", "app.worker.lease-seconds=30")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void valid_configuration_starts() {
        valid().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            AppProperties props = ctx.getBean(AppProperties.class);
            assertThat(props.worker().batchSize()).isEqualTo(10);
            assertThat(props.worker().leaseSeconds()).isEqualTo(30);
        });
    }

    @Test
    void to_string_never_contains_secret_or_url() {
        valid().run(ctx -> {
            AppProperties props = ctx.getBean(AppProperties.class);
            assertThat(props.toString()).doesNotContain(SECRET).doesNotContain(URL).doesNotContain("token-value");
            assertThat(props.github().toString()).doesNotContain(SECRET);
            assertThat(props.discord().toString()).doesNotContain(URL).doesNotContain("token-value");
        });
    }

    @Test
    void non_positive_worker_numbers_fail_binding() {
        valid().withPropertyValues("app.worker.batch-size=0").run(ctx -> assertThat(ctx).hasFailed());
        valid().withPropertyValues("app.worker.lease-seconds=-1").run(ctx -> assertThat(ctx).hasFailed());
    }
}
