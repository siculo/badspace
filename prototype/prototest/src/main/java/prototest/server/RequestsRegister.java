package prototest.server;

import prototest.api.errors.network.UnrecoverableProtocolException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class RequestsRegister {

    private final Map<Long, Request> requests = new ConcurrentHashMap<>();
    private final long timeout;

    private long lastExpiredId = -1;

    public RequestsRegister(long timeout) {
        this.timeout = timeout;
    }

    public synchronized void executeOnce(long requestId, Runnable operation) {
        validateRequestId(requestId);
        if (!requests.containsKey(requestId)) {
            operation.run();
            requests.put(requestId, new Request(null));
        }
        removeExpired();
    }

    public synchronized <T> T executeOnceAndReturn(long requestId, Supplier<T> function) {
        validateRequestId(requestId);
        T result = null;
        if (requests.containsKey(requestId)) {
            return (T) requests.get(requestId).result();
        }
        result = function.get();
        requests.put(requestId, new Request(result));
        removeExpired();
        return result;
    }

    private void removeExpired() {
        List<Long> expiredIds = requests.entrySet().stream()
                .filter(requestsEntry -> requestsEntry.getValue()
                        .isExpired(timeout)).map(Map.Entry::getKey).toList();
        expiredIds.stream().max(Long::compareTo).ifPresent(
                maxExpiredId -> lastExpiredId = maxExpiredId
        );
        expiredIds.forEach(requests::remove);
    }

    private void validateRequestId(long requestId) {
        if (requestId <= lastExpiredId) {
            throw new UnrecoverableProtocolException("Request %d expired".formatted(requestId));
        }
    }

    private record Request(Object result, long timestamp) {
        public Request(Object result) {
            this(result, System.currentTimeMillis());
        }

        public boolean isExpired(long timeout) {
            return System.currentTimeMillis() - timestamp > timeout;
        }
    }
}
