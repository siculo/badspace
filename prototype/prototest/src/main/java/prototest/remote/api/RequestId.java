package prototest.remote.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Identifies a request by its session token and its sequence in that session.
 * Sequences describe the order in which calls are started, regardless of arrival order.
 * Retries use the same ID as the original attempt.
 *
 * @param sessionToken the token issued by the server
 * @param sequence the non-negative sequence in that session
 */
public record RequestId(UUID sessionToken, long sequence) {
    public RequestId {
        Objects.requireNonNull(sessionToken, "sessionToken");
        if (sequence < 0) {
            throw new IllegalArgumentException("Negative request sequence: " + sequence);
        }
    }
}
