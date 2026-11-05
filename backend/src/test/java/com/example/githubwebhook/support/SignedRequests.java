package com.example.githubwebhook.support;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.json.JsonMapper;

/** Builds signed GitHub-style webhook requests for tests. */
public final class SignedRequests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private SignedRequests() {
    }

    public static String signature(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(IntegrationTestBase.WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A null signatureHeaderOrNull omits the header. */
    public static HttpRequest.Builder request(String url, String event, String deliveryId, byte[] body,
                                              String signatureHeaderOrNull) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("X-GitHub-Event", event)
                .header("X-GitHub-Delivery", deliveryId)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (signatureHeaderOrNull != null) {
            builder.header("X-Hub-Signature-256", signatureHeaderOrNull);
        }
        return builder;
    }

    /** Fills the issues/opened fixture; html_url is https://github.com/octo/repo/issues/{number}. */
    public static byte[] issuesOpened(long number, String title) {
        try (InputStream in = SignedRequests.class.getResourceAsStream("/github/issues-opened.json")) {
            String template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return template
                    .replace("__TITLE__", JSON.writeValueAsString(title))
                    .replace("__NUMBER__", Long.toString(number))
                    .getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
