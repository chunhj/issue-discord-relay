package com.example.githubwebhook.discord;

import com.example.githubwebhook.config.AppProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Sends one issue notification to the Discord webhook. Never throws for delivery problems; never leaks the URL. */
@Component
public class DiscordClient {

    private static final int TITLE_MAX = 256;
    private static final int BODY_ERROR_MAX = 300;

    private final RestClient restClient;
    private final JsonMapper json;
    private final URI uri;

    public DiscordClient(RestClient.Builder builder, JsonMapper json, AppProperties props) {
        this.restClient = builder.build();
        this.json = json;
        // wait=true makes Discord answer with the created message so its id can be stored (D-12).
        this.uri = UriComponentsBuilder.fromUriString(props.discord().webhookUrl()).queryParam("wait", "true")
                .build().toUri();
    }

    record Footer(String text) {
    }

    record Embed(String title, String url, Footer footer) {
    }

    record AllowedMentions(List<String> parse) {
    }

    record Payload(List<Embed> embeds, @JsonProperty("allowed_mentions") AllowedMentions allowedMentions) {
    }

    public SendResult send(String repo, long issueNumber, String title, String htmlUrl) {
        // Empty parse list: a hostile "@everyone" title must never ping anyone (D-11).
        Payload payload = new Payload(
                List.of(new Embed(composeTitle(issueNumber, title), htmlUrl, new Footer(repo))),
                new AllowedMentions(List.of()));
        try {
            return restClient.post().uri(uri).contentType(MediaType.APPLICATION_JSON).body(payload)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        // Status first: a stalled body must neither lose it nor turn a 2xx into a retry.
                        String retryAfterHeader = response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
                        String body = readBodyQuietly(response);
                        if (response.getStatusCode().is2xxSuccessful()) {
                            return SendResult.delivered(status, messageId(body));
                        }
                        Duration retryAfter = status == 429 ? parseRetryAfter(retryAfterHeader, body, json) : null;
                        return SendResult.httpFailure(status, "HTTP " + status + bodySnippet(body), retryAfter);
                    });
        } catch (RestClientException e) {
            // Exception messages embed the full URL including the token; keep code and class names only (D-11, D-13).
            IoKind kind = classify(e);
            Throwable cause = e.getCause();
            return SendResult.ioFailure(kind, kind.code() + " / " + e.getClass().getSimpleName()
                    + (cause == null ? "" : " / " + cause.getClass().getSimpleName()));
        }
    }

    /** Connect classes first: HttpConnectTimeoutException is a subclass of HttpTimeoutException. */
    private static IoKind classify(Throwable e) {
        boolean timeout = false;
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpConnectTimeoutException || t instanceof ConnectException) {
                return IoKind.CONNECT;
            }
            timeout |= t instanceof HttpTimeoutException;
        }
        return timeout ? IoKind.RESPONSE_TIMEOUT : IoKind.OTHER;
    }

    /** "#number title", at most 256 UTF-16 units including the trailing ellipsis (Discord embed limit). */
    static String composeTitle(long number, String title) {
        String full = "#" + number + " " + title;
        if (full.length() <= TITLE_MAX) {
            return full;
        }
        int end = TITLE_MAX - 1;
        if (Character.isHighSurrogate(full.charAt(end - 1))) {
            end--; // do not split a surrogate pair
        }
        return full.substring(0, end) + "…";
    }

    private static String readBodyQuietly(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        try {
            return response.bodyTo(String.class);
        } catch (RuntimeException e) {
            return null; // read timeout covers the body too; the status is already known
        }
    }

    /** Header first, then the body's retry_after (seconds, may be fractional); null when neither is usable. */
    static Duration parseRetryAfter(String headerValue, String body, JsonMapper json) {
        Duration fromHeader = toWait(headerValue == null ? null : parseSeconds(headerValue.trim()));
        if (fromHeader != null) {
            return fromHeader;
        }
        if (body == null) {
            return null;
        }
        try {
            JsonNode node = json.readTree(body).get("retry_after");
            return node != null && node.isNumber() ? toWait(node.asDouble()) : null;
        } catch (RuntimeException e) {
            return null; // hostile or garbled body: fall back to the ordinary backoff
        }
    }

    private static Double parseSeconds(String text) {
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Finite and positive only; clamped to one year before the millisecond conversion (overflow guard). */
    private static Duration toWait(Double seconds) {
        if (seconds == null || !Double.isFinite(seconds) || seconds <= 0) {
            return null;
        }
        return Duration.ofMillis(Math.round(Math.min(seconds, 31_536_000d) * 1000));
    }

    private String messageId(String body) {
        try {
            JsonNode id = json.readTree(body).get("id");
            return id == null || id.isNull() ? null : id.asString();
        } catch (RuntimeException e) {
            return null; // a delivered message is never turned into a failure because of its body
        }
    }

    private static String bodySnippet(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        return ": " + (body.length() > BODY_ERROR_MAX ? body.substring(0, BODY_ERROR_MAX) : body);
    }
}
