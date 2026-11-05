package com.example.githubwebhook.webhook;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

@RestController
public class WebhookController {

    public record StatusBody(String status) {
    }

    private final SignatureVerifier verifier;
    private final JsonMapper jsonMapper;
    private final WebhookIngestService ingestService;

    public WebhookController(SignatureVerifier verifier, JsonMapper jsonMapper, WebhookIngestService ingestService) {
        this.verifier = verifier;
        this.jsonMapper = jsonMapper;
        this.ingestService = ingestService;
    }

    // Tracer slice: routing, header validation, 1 MB cap and 400/413/500 mapping arrive in plan 01-03.
    @PostMapping("/webhooks/github")
    public ResponseEntity<StatusBody> receive(
            HttpServletRequest request,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(value = "X-GitHub-Event", required = false) String event,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId) throws IOException {
        byte[] raw = request.getInputStream().readAllBytes();

        // Signature first: no JSON parsing and no DB access for unauthenticated requests (D-07).
        if (!verifier.isValid(raw, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new StatusBody("unauthorized"));
        }

        IssuesPayload payload = jsonMapper.readValue(raw, IssuesPayload.class);
        // Reached only after ingest() returned, i.e. after the transaction committed (WEBHOOK-04).
        return switch (ingestService.ingest(deliveryId, payload)) {
            case ACCEPTED -> ResponseEntity.status(HttpStatus.ACCEPTED).body(new StatusBody("accepted"));
            case DUPLICATE -> ResponseEntity.ok(new StatusBody("duplicate"));
        };
    }
}
