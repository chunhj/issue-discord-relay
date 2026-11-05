package com.example.githubwebhook.discord;

import com.example.githubwebhook.config.AppProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URI;
import java.util.List;
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
                List.of(new Embed(embedTitle(issueNumber, title), htmlUrl, new Footer(repo))),
                new AllowedMentions(List.of()));
        try {
            return restClient.post().uri(uri).contentType(MediaType.APPLICATION_JSON).body(payload)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        String body = response.bodyTo(String.class);
                        return response.getStatusCode().is2xxSuccessful()
                                ? SendResult.delivered(status, messageId(body))
                                : SendResult.httpFailure(status, "HTTP " + status + bodySnippet(body));
                    });
        } catch (RestClientException e) {
            // Exception messages embed the full URL including the token; keep class names only (D-13).
            Throwable cause = e.getCause();
            return SendResult.ioFailure(e.getClass().getSimpleName()
                    + (cause == null ? "" : " / " + cause.getClass().getSimpleName()));
        }
    }

    static String embedTitle(long number, String title) {
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
