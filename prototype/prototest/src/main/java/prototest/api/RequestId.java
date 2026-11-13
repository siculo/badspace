package prototest.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Identifies a request by its proxy instance and its sequence in that proxy.
 * Sequences describe generation order, not arrival order, and are not comparable across proxies.
 * Retries use the same ID as the original attempt.
 *
 * @param proxyId the identity of one proxy instance
 * @param sequence the non-negative sequence in that proxy
 */
public record RequestId(UUID proxyId, long sequence) {
    public RequestId {
        Objects.requireNonNull(proxyId, "proxyId");
        if (sequence < 0) {
            throw new IllegalArgumentException("Negative request sequence: " + sequence);
        }
    }
}
