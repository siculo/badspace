package prototest.server;

import prototest.api.RequestId;
import prototest.api.errors.network.NodeRecoveryRequiredException;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public class RequestsRegister {

    // All requests and expiration limits are accessed under this register's monitor.
    private final Map<RequestId, Request> requests = new HashMap<>();
    private final Map<UUID, Long> lastExpiredSequences = new HashMap<>();
    private final long timeout;
    private final Clock clock;

    public RequestsRegister(long timeout) {
        this(timeout, Clock.systemUTC());
    }

    /** Creates a register with the clock used to measure response retention. */
    public RequestsRegister(long timeout, Clock clock) {
        this.timeout = timeout;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized void executeOnce(RequestId requestId, Runnable operation) {
        validateRequestId(requestId);
        if (!requests.containsKey(requestId)) {
            operation.run();
            requests.put(requestId, new Request(null, clock.millis()));
        }
        removeExpired();
    }

    public synchronized <T> T executeOnceAndReturn(RequestId requestId, Supplier<T> function) {
        validateRequestId(requestId);
        T result = null;
        if (requests.containsKey(requestId)) {
            return (T) requests.get(requestId).result();
        }
        result = function.get();
        requests.put(requestId, new Request(result, clock.millis()));
        removeExpired();
        return result;
    }

    private void removeExpired() {
        List<RequestId> expiredIds = requests.entrySet().stream()
                .filter(requestsEntry -> requestsEntry.getValue()
                        .isExpired(timeout, clock.millis())).map(Map.Entry::getKey).toList();
        expiredIds.forEach(requestId -> lastExpiredSequences.merge(
                requestId.proxyId(), requestId.sequence(), Math::max));
        expiredIds.forEach(requests::remove);
    }

    private void validateRequestId(RequestId requestId) {
        if (requestId.sequence() <= lastExpiredSequences.getOrDefault(requestId.proxyId(), -1L)) {
            throw new NodeRecoveryRequiredException("Request %s expired".formatted(requestId));
        }
    }

    private record Request(Object result, long timestamp) {
        public boolean isExpired(long timeout, long now) {
            return now - timestamp > timeout;
        }
    }
}
