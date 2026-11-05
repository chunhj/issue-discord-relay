package com.example.githubwebhook.webhook;

import com.example.githubwebhook.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** Verifies X-Hub-Signature-256 = "sha256=" + lowercase hex HMAC-SHA256 over the raw request bytes. */
@Component
public class SignatureVerifier {

    private static final String PREFIX = "sha256=";

    private final byte[] secret;

    public SignatureVerifier(AppProperties props) {
        this.secret = props.github().webhookSecret().getBytes(StandardCharsets.UTF_8);
    }

    public boolean isValid(byte[] rawBody, String headerValue) {
        if (headerValue == null || !headerValue.startsWith(PREFIX)) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            String expected = PREFIX + HexFormat.of().formatHex(mac.doFinal(rawBody));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    headerValue.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e); // server-side fault, never a client error
        }
    }
}
