package com.groupdrop.payment;

import com.groupdrop.common.GroupdropProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * PAY-04·16.3 웹훅 서명 검증. 서명 문자열은 {@code timestamp + "." + body}이며,
 * 타임스탬프를 서명 대상에 포함하는 이유는 본문만 서명하면 과거 요청을 그대로 재생할 수 있기 때문이다.
 */
@Component
public class WebhookSignatureVerifier {

    private static final String ALGORITHM = "HmacSHA256";

    private final GroupdropProperties properties;
    private final Clock clock;

    public WebhookSignatureVerifier(GroupdropProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public Verdict verify(String signature, String timestamp, String body) {
        if (signature == null || signature.isBlank() || timestamp == null || timestamp.isBlank()) {
            return Verdict.MISSING_HEADERS;
        }
        long epochSeconds;
        try {
            epochSeconds = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException exception) {
            return Verdict.BAD_TIMESTAMP;
        }
        Duration skew = Duration.between(Instant.ofEpochSecond(epochSeconds), Instant.now(clock)).abs();
        if (skew.compareTo(properties.webhook().timestampTolerance()) > 0) {
            return Verdict.STALE_TIMESTAMP;
        }
        String expected = sign(timestamp.trim(), body);
        boolean matches = MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
        return matches ? Verdict.VALID : Verdict.BAD_SIGNATURE;
    }

    String sign(String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(properties.webhook().secret().getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] digest = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("웹훅 서명을 계산할 수 없습니다.", exception);
        }
    }

    public enum Verdict {
        VALID,
        MISSING_HEADERS,
        BAD_TIMESTAMP,
        STALE_TIMESTAMP,
        BAD_SIGNATURE;

        public boolean valid() {
            return this == VALID;
        }
    }
}
