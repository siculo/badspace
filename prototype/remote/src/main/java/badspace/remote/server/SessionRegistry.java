package badspace.remote.server;

import badspace.remote.api.RequestId;
import badspace.remote.api.errors.NodeRecoveryRequiredException;
import badspace.remote.api.errors.SessionUnavailableException;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Keeps the sessions and their completed requests under the endpoint's lock. */
final class SessionRegistry {
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final long requestTimeout;
    private final long sessionTimeout;
    private final Clock clock;

    SessionRegistry(long requestTimeout, long sessionTimeout, Clock clock) {
        if (requestTimeout < 0 || sessionTimeout <= 0) {
            throw new IllegalArgumentException("Invalid retention timeouts: " + requestTimeout + ", " + sessionTimeout);
        }
        this.requestTimeout = requestTimeout;
        this.sessionTimeout = sessionTimeout;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    UUID open() {
        var token = UUID.randomUUID();
        sessions.put(token, new Session(clock.millis()));
        return token;
    }

    void revoke(UUID token) {
        sessions.remove(Objects.requireNonNull(token, "token"));
    }

    void revokeAll() {
        sessions.clear();
    }

    @SuppressWarnings("unchecked")
    <T> T executeOnceAndReturn(RequestId requestId, Supplier<T> operation) {
        var token = requestId.sessionToken();
        var session = sessions.get(token);
        if (session == null || removeExpired(token, session, clock.millis())) {
            throw new SessionUnavailableException("Session %s is unavailable".formatted(token));
        }
        long sequence = requestId.sequence();
        if (sequence <= session.lastExpiredSequence) {
            throw new NodeRecoveryRequiredException("Request %s expired".formatted(requestId));
        }
        var saved = session.requests.get(sequence);
        if (saved != null) {
            return (T) saved.result();
        }
        T result = operation.get();
        session.requests.put(sequence, new Request(result, clock.millis()));
        return result;
    }

    void removeExpired() {
        long now = clock.millis();
        var entries = sessions.values().iterator();
        while (entries.hasNext()) {
            var session = entries.next();
            removeExpiredRequests(session, now);
            if (isExpired(session, now)) {
                entries.remove();
            }
        }
    }

    private boolean removeExpired(UUID token, Session session, long now) {
        removeExpiredRequests(session, now);
        if (!isExpired(session, now)) {
            return false;
        }
        sessions.remove(token);
        return true;
    }

    private void removeExpiredRequests(Session session, long now) {
        if (session.requests.isEmpty()) {
            return;
        }
        var entries = session.requests.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            if (now - entry.getValue().completedAt() > requestTimeout) {
                session.lastExpiredSequence = Math.max(session.lastExpiredSequence, entry.getKey());
                entries.remove();
            }
        }
        if (session.requests.isEmpty()) {
            session.emptySince = now;
        }
    }

    private boolean isExpired(Session session, long now) {
        return session.requests.isEmpty() && now - session.emptySince >= sessionTimeout;
    }

    private static final class Session {
        private final Map<Long, Request> requests = new HashMap<>();
        private long lastExpiredSequence = -1;
        private long emptySince;

        private Session(long openedAt) {
            emptySince = openedAt;
        }
    }

    private record Request(Object result, long completedAt) {}
}
