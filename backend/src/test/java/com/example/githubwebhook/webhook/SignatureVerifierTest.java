package com.example.githubwebhook.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.githubwebhook.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Plain JUnit, no Spring, no Docker. Vector from GitHub docs "Validating webhook deliveries". */
class SignatureVerifierTest {

    private static final String SECRET = "It's a Secret to Everybody";
    private static final byte[] PAYLOAD = "Hello, World!".getBytes(StandardCharsets.UTF_8);
    private static final String VALID = "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17";

    private final SignatureVerifier verifier = verifierFor(SECRET);

    private static SignatureVerifier verifierFor(String secret) {
        return new SignatureVerifier(new AppProperties(new AppProperties.Github(secret),
                new AppProperties.Discord("http://localhost/x"), new AppProperties.Worker(true, 1000, 10, 30,
                        new AppProperties.Worker.Retry(5, Duration.ofSeconds(5), 3,
                                Duration.ofSeconds(300), 0.2, Duration.ofSeconds(3600)))));
    }

    @Test
    void official_vector_is_valid() {
        assertThat(verifier.isValid(PAYLOAD, VALID)).isTrue();
    }

    @Test
    void null_header_is_invalid() {
        assertThat(verifier.isValid(PAYLOAD, null)).isFalse();
    }

    @Test
    void sha1_prefix_is_invalid() {
        assertThat(verifier.isValid(PAYLOAD, VALID.replace("sha256=", "sha1="))).isFalse();
    }

    @Test
    void uppercase_hex_of_correct_digest_is_invalid() {
        assertThat(verifier.isValid(PAYLOAD, "sha256=" + VALID.substring(7).toUpperCase())).isFalse();
    }

    @Test
    void truncated_digest_is_invalid() {
        assertThat(verifier.isValid(PAYLOAD, VALID.substring(0, VALID.length() - 2))).isFalse();
    }

    @Test
    void wrong_secret_is_invalid() {
        assertThat(verifierFor("another secret").isValid(PAYLOAD, VALID)).isFalse();
    }

    @Test
    void empty_body_with_the_vector_header_is_invalid() {
        assertThat(verifier.isValid(new byte[0], VALID)).isFalse();
    }
}
