package com.example.githubwebhook.webhook;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@RestController
public class WebhookController {

    public record StatusBody(String status) {
    }

    /** D-08: bodies above 1 MB are rejected before any signature work. */
    static final int MAX_BODY_BYTES = 1_048_576;

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final SignatureVerifier verifier;
    private final JsonMapper jsonMapper;
    private final WebhookIngestService ingestService;

    public WebhookController(SignatureVerifier verifier, JsonMapper jsonMapper, WebhookIngestService ingestService) {
        this.verifier = verifier;
        this.jsonMapper = jsonMapper;
        this.ingestService = ingestService;
    }

    /**
     * Check order is part of the contract (D-06/D-07): size cap, signature, required headers, event routing,
     * JSON parse, ingest. Nothing before the signature check parses, validates or touches the database.
     */
    @PostMapping("/webhooks/github")
    public ResponseEntity<StatusBody> receive(
            HttpServletRequest request,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(value = "X-GitHub-Event", required = false) String event,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId) throws IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            return respond(HttpStatus.CONTENT_TOO_LARGE, "too_large");
        }
        // Bounded read (not a framework body binding): a chunked body cannot make us buffer more than cap + 1.
        byte[] raw = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (raw.length > MAX_BODY_BYTES) {
            return respond(HttpStatus.CONTENT_TOO_LARGE, "too_large");
        }

        if (!verifier.isValid(raw, signature)) {
            return respond(HttpStatus.UNAUTHORIZED, "unauthorized");
        }

        if (isBlank(event) || isBlank(deliveryId)) {
            return respond(HttpStatus.BAD_REQUEST, "bad_request");
        }
        if (!"issues".equals(event)) {
            return respond(HttpStatus.OK, "ignored");
        }

        IssuesPayload payload;
        try {
            payload = jsonMapper.readValue(raw, IssuesPayload.class);
        } catch (JacksonException e) {
            return respond(HttpStatus.BAD_REQUEST, "bad_request");
        }
        if (!isComplete(payload)) {
            return respond(HttpStatus.BAD_REQUEST, "bad_request");
        }
        if (!"opened".equals(payload.action())) {
            return respond(HttpStatus.OK, "ignored");
        }

        try {
            // The response below is built only after ingest() returned, i.e. after commit (WEBHOOK-04).
            return switch (ingestService.ingest(deliveryId, payload)) {
                case ACCEPTED -> respond(HttpStatus.ACCEPTED, "accepted");
                case DUPLICATE -> respond(HttpStatus.OK, "duplicate");
            };
        } catch (RuntimeException e) {
            // Class name only: the delivery header is not covered by the signature, so never log header/payload text.
            log.error("webhook ingest failed: {}", e.getClass().getSimpleName());
            return respond(HttpStatus.INTERNAL_SERVER_ERROR, "error");
        }
    }

    private static ResponseEntity<StatusBody> respond(HttpStatus status, String body) {
        return ResponseEntity.status(status).body(new StatusBody(body));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Required fields present, and no NUL (PostgreSQL text cannot store it). */
    private static boolean isComplete(IssuesPayload p) {
        return p != null && p.issue() != null && p.repository() != null && p.issue().number() != null
                && storable(p.issue().htmlUrl(), true) && storable(p.repository().fullName(), true)
                && storable(p.issue().title(), false);
    }

    private static boolean storable(String s, boolean nonBlank) {
        return s != null && (!nonBlank || !s.isBlank()) && s.indexOf('\0') < 0;
    }
}
